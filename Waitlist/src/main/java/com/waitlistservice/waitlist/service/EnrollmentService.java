package com.waitlistservice.waitlist.service;

import com.waitlistservice.waitlist.domain.OfferingView;
import com.waitlistservice.waitlist.domain.RegistrationOutcome;
import com.waitlistservice.waitlist.domain.RegistrationResult;
import com.waitlistservice.waitlist.persistence.RegistrationRepository;
import com.waitlistservice.waitlist.persistence.RegistrationRepository.OfferingRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class EnrollmentService {

    private final RegistrationRepository repository;

    public EnrollmentService(RegistrationRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RegistrationResult enroll(String learnerId, String offeringId) {
        String learner = normalize(learnerId);
        String offering = normalize(offeringId);
        if (learner.isEmpty() || offering.isEmpty()) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    learnerId,
                    offeringId,
                    "Learner id and offering id are required."
            );
        }

        Optional<OfferingRecord> locked = repository.lockOffering(offering);
        if (locked.isEmpty() || !repository.learnerExists(learner)) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    learner,
                    offering,
                    "Learner and offering must both exist."
            );
        }

        if (repository.isEnrolled(offering, learner)) {
            return RegistrationResult.of(
                    RegistrationOutcome.ALREADY_ENROLLED,
                    learner,
                    offering,
                    "Learner is already enrolled."
            );
        }
        if (repository.isWaitlisted(offering, learner)) {
            return RegistrationResult.of(
                    RegistrationOutcome.ALREADY_WAITLISTED,
                    learner,
                    offering,
                    "Learner is already waitlisted."
            );
        }
        if (!repository.isEligible(learner, offering)) {
            return RegistrationResult.of(
                    RegistrationOutcome.PREREQUISITE_NOT_MET,
                    learner,
                    offering,
                    "Learner has not completed every prerequisite."
            );
        }

        OfferingRecord offeringRecord = locked.get();
        if (repository.countEnrolled(offering) >= offeringRecord.capacity()) {
            repository.appendWaitlist(offering, learner);
            assertInvariants(offering, offeringRecord.capacity());
            return RegistrationResult.of(
                    RegistrationOutcome.WAITLISTED,
                    learner,
                    offering,
                    "Offering is full. Learner joined the waitlist."
            );
        }

        repository.insertEnrollment(offering, learner);
        assertInvariants(offering, offeringRecord.capacity());
        return RegistrationResult.of(
                RegistrationOutcome.ENROLLED,
                learner,
                offering,
                "Learner enrolled."
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RegistrationResult drop(String learnerId, String offeringId) {
        String learner = normalize(learnerId);
        String offering = normalize(offeringId);
        if (learner.isEmpty() || offering.isEmpty()) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    learnerId,
                    offeringId,
                    "Learner id and offering id are required."
            );
        }

        Optional<OfferingRecord> locked = repository.lockOffering(offering);
        if (locked.isEmpty()) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    learner,
                    offering,
                    "Offering does not exist."
            );
        }
        if (!repository.isEnrolled(offering, learner)) {
            return RegistrationResult.of(
                    RegistrationOutcome.NOT_ENROLLED,
                    learner,
                    offering,
                    "Learner is not enrolled."
            );
        }

        repository.deleteEnrollment(offering, learner);
        String promotedLearnerId = promoteWhileLocked(offering, locked.get().capacity());
        assertInvariants(offering, locked.get().capacity());
        return RegistrationResult.dropped(learner, offering, promotedLearnerId);
    }

    /**
     * Fills one already-open seat from the waitlist. Drop calls the same promotion
     * steps inside its own transaction after releasing a seat.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RegistrationResult promoteNextEligible(String offeringId) {
        String offering = normalize(offeringId);
        if (offering.isEmpty()) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    null,
                    offeringId,
                    "Offering id is required."
            );
        }

        Optional<OfferingRecord> locked = repository.lockOffering(offering);
        if (locked.isEmpty()) {
            return RegistrationResult.of(
                    RegistrationOutcome.INVALID_REQUEST,
                    null,
                    offering,
                    "Offering does not exist."
            );
        }

        int capacity = locked.get().capacity();
        if (repository.countEnrolled(offering) >= capacity) {
            return RegistrationResult.of(
                    RegistrationOutcome.NO_PROMOTION,
                    null,
                    offering,
                    "No open seat."
            );
        }

        String promotedLearnerId = promoteWhileLocked(offering, capacity);
        assertInvariants(offering, capacity);
        if (promotedLearnerId == null) {
            return RegistrationResult.of(
                    RegistrationOutcome.NO_PROMOTION,
                    null,
                    offering,
                    "No eligible waitlisted learner."
            );
        }
        return new RegistrationResult(
                RegistrationOutcome.ENROLLED,
                promotedLearnerId,
                offering,
                promotedLearnerId,
                "Promoted the earliest eligible waitlisted learner."
        );
    }

    @Transactional(readOnly = true)
    public Optional<OfferingView> getOffering(String offeringId) {
        if (offeringId == null || offeringId.trim().isEmpty()) {
            return Optional.empty();
        }
        return repository.findOffering(offeringId.trim());
    }

    private String promoteWhileLocked(String offeringId, int capacity) {
        if (repository.countEnrolled(offeringId) >= capacity) {
            return null;
        }
        for (String candidate : repository.waitlistedLearnerIds(offeringId)) {
            if (!repository.isEligible(candidate, offeringId)) {
                repository.deleteWaitlistEntry(offeringId, candidate);
                continue;
            }
            repository.deleteWaitlistEntry(offeringId, candidate);
            repository.insertEnrollment(offeringId, candidate);
            return candidate;
        }
        return null;
    }

    private void assertInvariants(String offeringId, int capacity) {
        int enrolled = repository.countEnrolled(offeringId);
        if (enrolled > capacity) {
            throw new IllegalStateException("Enrollment count exceeds capacity for offering " + offeringId);
        }
        if (repository.offeringCapacity(offeringId) != capacity) {
            throw new IllegalStateException("Offering capacity changed during enrollment for " + offeringId);
        }
        if (repository.countEnrolledAndWaitlisted(offeringId) > 0) {
            throw new IllegalStateException("A learner is both enrolled and waitlisted for offering " + offeringId);
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim();
    }
}
