package com.waitlistservice.waitlist;

import com.waitlistservice.waitlist.domain.OfferingView;
import com.waitlistservice.waitlist.domain.RegistrationOutcome;
import com.waitlistservice.waitlist.domain.RegistrationResult;
import com.waitlistservice.waitlist.service.CourseSetupService;
import com.waitlistservice.waitlist.service.EnrollmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class EnrollmentBehaviorTest {

    private static final String INTRO = "intro";

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private CourseSetupService courseSetupService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM waitlist_entry");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM waitlist_sequence");
        jdbc.update("DELETE FROM offering_prerequisite");
        jdbc.update("DELETE FROM learner_completion");
        jdbc.update("DELETE FROM course_offering");
        jdbc.update("DELETE FROM learner");
    }

    @Test
    void eligibleLearnerEnrollsWhenCapacityIsAvailable() {
        createOffering("offer-open", 2, INTRO);
        createLearner("ada", INTRO);

        RegistrationResult result = enrollmentService.enroll("ada", "offer-open");

        assertEquals(RegistrationOutcome.ENROLLED, result.outcome());
        assertEquals(List.of("ada"), view("offer-open").enrolledLearnerIds());
        assertTrue(view("offer-open").waitlistedLearnerIds().isEmpty());
        assertEquals(2, view("offer-open").capacity());
    }

    @Test
    void eligibleLearnerJoinsWaitlistWhenCourseIsFull() {
        createOffering("offer-full", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        assertEquals(RegistrationOutcome.ENROLLED, enrollmentService.enroll("ada", "offer-full").outcome());

        RegistrationResult result = enrollmentService.enroll("bea", "offer-full");

        assertEquals(RegistrationOutcome.WAITLISTED, result.outcome());
        OfferingView state = view("offer-full");
        assertEquals(List.of("ada"), state.enrolledLearnerIds());
        assertEquals(List.of("bea"), state.waitlistedLearnerIds());
        assertEquals(1, state.capacity());
    }

    @Test
    void duplicateEnrollmentRequestDoesNotCreateAnotherEnrollment() {
        createOffering("offer-dup-enroll", 2, INTRO);
        createLearner("ada", INTRO);
        assertEquals(RegistrationOutcome.ENROLLED, enrollmentService.enroll("ada", "offer-dup-enroll").outcome());

        RegistrationResult duplicate = enrollmentService.enroll("ada", "offer-dup-enroll");

        assertEquals(RegistrationOutcome.ALREADY_ENROLLED, duplicate.outcome());
        assertEquals(List.of("ada"), view("offer-dup-enroll").enrolledLearnerIds());
        assertTrue(view("offer-dup-enroll").waitlistedLearnerIds().isEmpty());
    }

    @Test
    void duplicateWaitlistRequestDoesNotCreateAnotherQueueEntry() {
        createOffering("offer-dup-wait", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-dup-wait");
        assertEquals(RegistrationOutcome.WAITLISTED, enrollmentService.enroll("bea", "offer-dup-wait").outcome());

        RegistrationResult duplicate = enrollmentService.enroll("bea", "offer-dup-wait");

        assertEquals(RegistrationOutcome.ALREADY_WAITLISTED, duplicate.outcome());
        assertEquals(List.of("bea"), view("offer-dup-wait").waitlistedLearnerIds());
    }

    @Test
    void ineligibleLearnerCannotEnroll() {
        createOffering("offer-prereq", 2, INTRO);
        createLearner("ada");

        RegistrationResult result = enrollmentService.enroll("ada", "offer-prereq");

        assertEquals(RegistrationOutcome.PREREQUISITE_NOT_MET, result.outcome());
        assertTrue(view("offer-prereq").enrolledLearnerIds().isEmpty());
        assertTrue(view("offer-prereq").waitlistedLearnerIds().isEmpty());
    }

    @Test
    void ineligibleLearnerCannotJoinTheWaitlist() {
        createOffering("offer-prereq-full", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea");
        enrollmentService.enroll("ada", "offer-prereq-full");

        RegistrationResult result = enrollmentService.enroll("bea", "offer-prereq-full");

        assertEquals(RegistrationOutcome.PREREQUISITE_NOT_MET, result.outcome());
        assertEquals(List.of("ada"), view("offer-prereq-full").enrolledLearnerIds());
        assertTrue(view("offer-prereq-full").waitlistedLearnerIds().isEmpty());
    }

    @Test
    void invalidEnrollmentRequestDoesNotChangeState() {
        createOffering("offer-invalid-enroll", 1, INTRO);
        createLearner("ada", INTRO);
        enrollmentService.enroll("ada", "offer-invalid-enroll");
        OfferingView before = view("offer-invalid-enroll");

        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.enroll(" ", "offer-invalid-enroll").outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.enroll("ada", null).outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.enroll("missing-learner", "offer-invalid-enroll").outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.enroll("ada", "missing-offering").outcome());

        assertEquals(before, view("offer-invalid-enroll"));
    }

    @Test
    void enrollmentCannotExceedConfiguredCapacity() {
        createOffering("offer-cap", 3, INTRO);
        for (String learnerId : List.of("a", "b", "c", "d", "e")) {
            createLearner(learnerId, INTRO);
        }

        assertEquals(RegistrationOutcome.ENROLLED, enrollmentService.enroll("a", "offer-cap").outcome());
        assertEquals(RegistrationOutcome.ENROLLED, enrollmentService.enroll("b", "offer-cap").outcome());
        assertEquals(RegistrationOutcome.ENROLLED, enrollmentService.enroll("c", "offer-cap").outcome());
        assertEquals(RegistrationOutcome.WAITLISTED, enrollmentService.enroll("d", "offer-cap").outcome());
        assertEquals(RegistrationOutcome.WAITLISTED, enrollmentService.enroll("e", "offer-cap").outcome());

        OfferingView state = view("offer-cap");
        assertEquals(List.of("a", "b", "c"), state.enrolledLearnerIds());
        assertEquals(List.of("d", "e"), state.waitlistedLearnerIds());
        assertConsistent(state, 3);
    }

    @Test
    void droppingEnrolledLearnerOpensASeatWhenWaitlistIsEmpty() {
        createOffering("offer-drop-empty", 1, INTRO);
        createLearner("ada", INTRO);
        enrollmentService.enroll("ada", "offer-drop-empty");

        RegistrationResult result = enrollmentService.drop("ada", "offer-drop-empty");

        assertEquals(RegistrationOutcome.DROPPED, result.outcome());
        assertNull(result.promotedLearnerId());
        OfferingView state = view("offer-drop-empty");
        assertTrue(state.enrolledLearnerIds().isEmpty());
        assertTrue(state.waitlistedLearnerIds().isEmpty());
        assertEquals(1, state.capacity());
    }

    @Test
    void earliestEligibleWaitlistedLearnerIsPromoted() {
        createOffering("offer-promote", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        createLearner("cy", INTRO);
        enrollmentService.enroll("ada", "offer-promote");
        enrollmentService.enroll("bea", "offer-promote");
        enrollmentService.enroll("cy", "offer-promote");

        RegistrationResult result = enrollmentService.drop("ada", "offer-promote");

        assertEquals(RegistrationOutcome.DROPPED, result.outcome());
        assertEquals("bea", result.promotedLearnerId());
        assertEquals(List.of("bea"), view("offer-promote").enrolledLearnerIds());
        assertEquals(List.of("cy"), view("offer-promote").waitlistedLearnerIds());
    }

    @Test
    void fifoOrderOfRemainingWaitlistedLearnersIsPreserved() {
        createOffering("offer-fifo", 1, INTRO);
        for (String learnerId : List.of("ada", "bea", "cy", "dee")) {
            createLearner(learnerId, INTRO);
            enrollmentService.enroll(learnerId, "offer-fifo");
        }

        enrollmentService.drop("ada", "offer-fifo");

        OfferingView state = view("offer-fifo");
        assertEquals(List.of("bea"), state.enrolledLearnerIds());
        assertEquals(List.of("cy", "dee"), state.waitlistedLearnerIds());
    }

    @Test
    void ineligibleLearnerAtFrontOfWaitlistIsSkippedAndRemoved() {
        createOffering("offer-skip-front", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        createLearner("cy", INTRO);
        enrollmentService.enroll("ada", "offer-skip-front");
        enrollmentService.enroll("bea", "offer-skip-front");
        enrollmentService.enroll("cy", "offer-skip-front");
        courseSetupService.replaceCompletions("bea", Set.of());

        RegistrationResult result = enrollmentService.drop("ada", "offer-skip-front");

        assertEquals("cy", result.promotedLearnerId());
        OfferingView state = view("offer-skip-front");
        assertEquals(List.of("cy"), state.enrolledLearnerIds());
        assertTrue(state.waitlistedLearnerIds().isEmpty());
        assertTrue(!state.enrolledLearnerIds().contains("bea"));
    }

    @Test
    void nextEligibleLearnerIsPromotedAfterMultipleIneligibleCandidates() {
        createOffering("offer-skip-many", 1, INTRO);
        for (String learnerId : List.of("ada", "bea", "cy", "dee")) {
            createLearner(learnerId, INTRO);
            enrollmentService.enroll(learnerId, "offer-skip-many");
        }
        courseSetupService.replaceCompletions("bea", Set.of());
        courseSetupService.replaceCompletions("cy", Set.of());

        RegistrationResult result = enrollmentService.drop("ada", "offer-skip-many");

        assertEquals("dee", result.promotedLearnerId());
        OfferingView state = view("offer-skip-many");
        assertEquals(List.of("dee"), state.enrolledLearnerIds());
        assertTrue(state.waitlistedLearnerIds().isEmpty());
        assertTrue(!state.enrolledLearnerIds().contains("bea"));
        assertTrue(!state.enrolledLearnerIds().contains("cy"));
    }

    @Test
    void noEligibleLearnerLeavesTheSeatAvailable() {
        createOffering("offer-none-eligible", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        createLearner("cy", INTRO);
        enrollmentService.enroll("ada", "offer-none-eligible");
        enrollmentService.enroll("bea", "offer-none-eligible");
        enrollmentService.enroll("cy", "offer-none-eligible");
        courseSetupService.replaceCompletions("bea", Set.of());
        courseSetupService.replaceCompletions("cy", Set.of());

        RegistrationResult result = enrollmentService.drop("ada", "offer-none-eligible");

        assertEquals(RegistrationOutcome.DROPPED, result.outcome());
        assertNull(result.promotedLearnerId());
        OfferingView state = view("offer-none-eligible");
        assertTrue(state.enrolledLearnerIds().isEmpty());
        assertTrue(state.waitlistedLearnerIds().isEmpty());
    }

    @Test
    void ineligibleLearnerIsNeverPromoted() {
        createOffering("offer-never", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-never");
        enrollmentService.enroll("bea", "offer-never");
        courseSetupService.replaceCompletions("bea", Set.of());

        RegistrationResult result = enrollmentService.drop("ada", "offer-never");

        assertNull(result.promotedLearnerId());
        OfferingView state = view("offer-never");
        assertTrue(state.enrolledLearnerIds().isEmpty());
        assertTrue(!state.waitlistedLearnerIds().contains("bea"));
        assertTrue(!state.enrolledLearnerIds().contains("bea"));
    }

    @Test
    void droppingLearnerWhoIsNotEnrolledReturnsClearResult() {
        createOffering("offer-not-enrolled", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-not-enrolled");
        enrollmentService.enroll("bea", "offer-not-enrolled");
        OfferingView before = view("offer-not-enrolled");

        RegistrationResult waitlistedDrop = enrollmentService.drop("bea", "offer-not-enrolled");
        RegistrationResult unknownLearner = enrollmentService.drop("missing-learner", "offer-not-enrolled");

        assertEquals(RegistrationOutcome.NOT_ENROLLED, waitlistedDrop.outcome());
        assertEquals(RegistrationOutcome.NOT_ENROLLED, unknownLearner.outcome());
        assertEquals(before, view("offer-not-enrolled"));
    }

    @Test
    void repeatedDropDoesNotModifyEnrollmentOrWaitlist() {
        createOffering("offer-repeat-drop", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-repeat-drop");
        enrollmentService.enroll("bea", "offer-repeat-drop");
        RegistrationResult first = enrollmentService.drop("ada", "offer-repeat-drop");
        OfferingView afterFirst = view("offer-repeat-drop");

        RegistrationResult second = enrollmentService.drop("ada", "offer-repeat-drop");

        assertEquals(RegistrationOutcome.DROPPED, first.outcome());
        assertEquals("bea", first.promotedLearnerId());
        assertEquals(RegistrationOutcome.NOT_ENROLLED, second.outcome());
        assertEquals(afterFirst, view("offer-repeat-drop"));
    }

    @Test
    void invalidDropRequestDoesNotCorruptState() {
        createOffering("offer-invalid-drop", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-invalid-drop");
        enrollmentService.enroll("bea", "offer-invalid-drop");
        OfferingView before = view("offer-invalid-drop");

        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.drop(" ", "offer-invalid-drop").outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.drop("ada", " ").outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.drop("ada", "missing-offering").outcome());

        assertEquals(before, view("offer-invalid-drop"));
    }

    @Test
    void droppedLearnerCanSubmitAFreshEnrollmentRequest() {
        createOffering("offer-reenroll", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-reenroll");
        enrollmentService.enroll("bea", "offer-reenroll");

        assertEquals("bea", enrollmentService.drop("ada", "offer-reenroll").promotedLearnerId());
        RegistrationResult again = enrollmentService.enroll("ada", "offer-reenroll");

        assertEquals(RegistrationOutcome.WAITLISTED, again.outcome());
        assertEquals(List.of("bea"), view("offer-reenroll").enrolledLearnerIds());
        assertEquals(List.of("ada"), view("offer-reenroll").waitlistedLearnerIds());
    }

    @Test
    void alreadyEnrolledOrWaitlistedIsReportedBeforePrerequisiteRecheck() {
        createOffering("offer-precedence", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-precedence");
        enrollmentService.enroll("bea", "offer-precedence");
        courseSetupService.replaceCompletions("ada", Set.of());
        courseSetupService.replaceCompletions("bea", Set.of());

        assertEquals(RegistrationOutcome.ALREADY_ENROLLED, enrollmentService.enroll("ada", "offer-precedence").outcome());
        assertEquals(RegistrationOutcome.ALREADY_WAITLISTED, enrollmentService.enroll("bea", "offer-precedence").outcome());
        assertEquals(List.of("ada"), view("offer-precedence").enrolledLearnerIds());
        assertEquals(List.of("bea"), view("offer-precedence").waitlistedLearnerIds());
    }

    @Test
    void promoteNextEligibleFillsAnOpenSeatAndDoesNothingWhenFull() {
        createOffering("offer-manual-seat", 1, INTRO);
        createLearner("ada", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-manual-seat");
        enrollmentService.enroll("bea", "offer-manual-seat");
        OfferingView full = view("offer-manual-seat");

        RegistrationResult blocked = enrollmentService.promoteNextEligible("offer-manual-seat");

        assertEquals(RegistrationOutcome.NO_PROMOTION, blocked.outcome());
        assertEquals(full, view("offer-manual-seat"));

        jdbc.update("DELETE FROM enrollment WHERE offering_id = ? AND learner_id = ?", "offer-manual-seat", "ada");
        RegistrationResult promoted = enrollmentService.promoteNextEligible("offer-manual-seat");

        assertEquals(RegistrationOutcome.ENROLLED, promoted.outcome());
        assertEquals("bea", promoted.learnerId());
        assertEquals(List.of("bea"), view("offer-manual-seat").enrolledLearnerIds());
        assertTrue(view("offer-manual-seat").waitlistedLearnerIds().isEmpty());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.promoteNextEligible(" ").outcome());
        assertEquals(RegistrationOutcome.INVALID_REQUEST, enrollmentService.promoteNextEligible("missing-offering").outcome());
    }

    @Test
    void explicitPromotionWithNobodyWaitingLeavesTheSeatOpen() {
        createOffering("offer-empty-seat", 1, INTRO);

        RegistrationResult result = enrollmentService.promoteNextEligible("offer-empty-seat");

        assertEquals(RegistrationOutcome.NO_PROMOTION, result.outcome());
        assertTrue(view("offer-empty-seat").enrolledLearnerIds().isEmpty());
    }

    @Test
    void promotionFailureRollsBackTheDrop() {
        createOffering("offer-rollback", 3, INTRO);
        createLearner("ada", INTRO);
        createLearner("cy", INTRO);
        createLearner("bea", INTRO);
        enrollmentService.enroll("ada", "offer-rollback");
        enrollmentService.enroll("cy", "offer-rollback");
        assertEquals(RegistrationOutcome.WAITLISTED, enrollmentService.enroll("bea", "offer-rollback").outcome());
        jdbc.update("INSERT INTO enrollment(offering_id, learner_id) VALUES (?, ?)", "offer-rollback", "bea");
        OfferingView before = view("offer-rollback");
        assertEquals(List.of("ada", "cy", "bea"), before.enrolledLearnerIds());
        assertEquals(List.of("bea"), before.waitlistedLearnerIds());

        assertThrows(DataAccessException.class, () -> enrollmentService.drop("ada", "offer-rollback"));

        OfferingView after = view("offer-rollback");
        assertEquals(List.of("ada", "cy", "bea"), after.enrolledLearnerIds());
        assertEquals(List.of("bea"), after.waitlistedLearnerIds());
    }

    @Test
    @Timeout(30)
    void concurrentEnrollmentsNeverExceedCapacity() throws Exception {
        int capacity = 5;
        int attempts = 25;
        createOffering("offer-concurrent", capacity, INTRO);
        for (int i = 0; i < attempts; i++) {
            createLearner("learner-" + i, INTRO);
        }

        List<RegistrationResult> results = runTogether(attempts, index ->
                enrollmentService.enroll("learner-" + index, "offer-concurrent"));

        long enrolled = results.stream().filter(result -> result.outcome() == RegistrationOutcome.ENROLLED).count();
        long waitlisted = results.stream().filter(result -> result.outcome() == RegistrationOutcome.WAITLISTED).count();
        OfferingView state = view("offer-concurrent");
        assertEquals(capacity, enrolled);
        assertEquals(attempts - capacity, waitlisted);
        assertEquals(capacity, state.enrolledLearnerIds().size());
        assertEquals(attempts - capacity, state.waitlistedLearnerIds().size());
        assertConsistent(state, capacity);
    }

    @Test
    @Timeout(30)
    void concurrentDuplicateRequestsDoNotCreateDuplicateRegistrations() throws Exception {
        createOffering("offer-concurrent-dup", 3, INTRO);
        createLearner("ada", INTRO);

        List<RegistrationResult> results = runTogether(12, index -> enrollmentService.enroll("ada", "offer-concurrent-dup"));

        long enrolled = results.stream().filter(result -> result.outcome() == RegistrationOutcome.ENROLLED).count();
        long already = results.stream().filter(result -> result.outcome() == RegistrationOutcome.ALREADY_ENROLLED).count();
        assertEquals(1, enrolled);
        assertEquals(11, already);
        OfferingView state = view("offer-concurrent-dup");
        assertEquals(List.of("ada"), state.enrolledLearnerIds());
        assertTrue(state.waitlistedLearnerIds().isEmpty());
    }

    @Test
    @Timeout(30)
    void concurrentDropsPreserveEnrollmentAndWaitlistInvariants() throws Exception {
        createOffering("offer-concurrent-drop", 2, INTRO);
        for (String learnerId : List.of("a", "b", "c", "d", "e", "f")) {
            createLearner(learnerId, INTRO);
            enrollmentService.enroll(learnerId, "offer-concurrent-drop");
        }

        List<RegistrationResult> results = runTogether(List.of("a", "b"), enrollmentService::drop);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == RegistrationOutcome.DROPPED));
        OfferingView state = view("offer-concurrent-drop");
        assertEquals(Set.of("c", "d"), new HashSet<>(state.enrolledLearnerIds()));
        assertEquals(List.of("e", "f"), state.waitlistedLearnerIds());
        assertConsistent(state, 2);
    }

    private void createOffering(String offeringId, int capacity, String... prerequisites) {
        courseSetupService.createOffering(offeringId, capacity, Set.of(prerequisites));
    }

    private void createLearner(String learnerId, String... completedCourseIds) {
        courseSetupService.createLearner(learnerId, Set.of(completedCourseIds));
    }

    private OfferingView view(String offeringId) {
        return enrollmentService.getOffering(offeringId).orElseThrow();
    }

    private static void assertConsistent(OfferingView state, int capacity) {
        assertEquals(capacity, state.capacity());
        assertTrue(state.enrolledLearnerIds().size() <= capacity);
        assertEquals(state.enrolledLearnerIds().size(), new HashSet<>(state.enrolledLearnerIds()).size());
        assertEquals(state.waitlistedLearnerIds().size(), new HashSet<>(state.waitlistedLearnerIds()).size());
        assertTrue(Collections.disjoint(state.enrolledLearnerIds(), state.waitlistedLearnerIds()));
    }

    private List<RegistrationResult> runTogether(int attempts, EnrollmentAttempt attempt) throws Exception {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            indexes.add(i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RegistrationResult>> futures = new ArrayList<>();
            for (Integer index : indexes) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return attempt.enroll(index);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<RegistrationResult> results = new ArrayList<>();
            for (Future<RegistrationResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<RegistrationResult> runTogether(List<String> learnerIds, DropAttempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(learnerIds.size());
        CountDownLatch ready = new CountDownLatch(learnerIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RegistrationResult>> futures = new ArrayList<>();
            for (String learnerId : learnerIds) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return attempt.drop(learnerId, "offer-concurrent-drop");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<RegistrationResult> results = new ArrayList<>();
            for (Future<RegistrationResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent tasks to start.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface EnrollmentAttempt {
        RegistrationResult enroll(int index) throws ExecutionException, InterruptedException;
    }

    @FunctionalInterface
    private interface DropAttempt {
        RegistrationResult drop(String learnerId, String offeringId);
    }
}
