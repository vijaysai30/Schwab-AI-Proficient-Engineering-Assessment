package com.waitlistservice.waitlist.service;

import com.waitlistservice.waitlist.domain.AlreadyExistsException;
import com.waitlistservice.waitlist.domain.InvalidSetupException;
import com.waitlistservice.waitlist.domain.LearnerView;
import com.waitlistservice.waitlist.domain.UnknownResourceException;
import com.waitlistservice.waitlist.persistence.RegistrationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class CourseSetupService {

    private final RegistrationRepository repository;

    public CourseSetupService(RegistrationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void createOffering(String offeringId, Integer capacity, Set<String> prerequisiteCourseIds) {
        String id = requiredId(offeringId, "Offering id");
        if (capacity == null || capacity < 0) {
            throw new InvalidSetupException("Capacity must be zero or greater.");
        }
        Set<String> prerequisites = normalizeCourseIds(prerequisiteCourseIds);
        try {
            repository.insertOffering(id, capacity, prerequisites);
        } catch (DuplicateKeyException ex) {
            throw new AlreadyExistsException("Offering already exists: " + id);
        }
    }

    @Transactional
    public LearnerView createLearner(String learnerId, Set<String> completedCourseIds) {
        String id = requiredId(learnerId, "Learner id");
        Set<String> completed = normalizeCourseIds(completedCourseIds);
        try {
            repository.insertLearner(id, completed);
        } catch (DuplicateKeyException ex) {
            throw new AlreadyExistsException("Learner already exists: " + id);
        }
        return repository.findLearner(id)
                .orElseThrow(() -> new IllegalStateException("Learner was not saved: " + id));
    }

    @Transactional
    public LearnerView replaceCompletions(String learnerId, Set<String> completedCourseIds) {
        String id = requiredId(learnerId, "Learner id");
        if (!repository.learnerExists(id)) {
            throw new UnknownResourceException("Learner does not exist: " + id);
        }
        Set<String> completed = normalizeCourseIds(completedCourseIds);
        repository.replaceCompletions(id, completed);
        return repository.findLearner(id)
                .orElseThrow(() -> new IllegalStateException("Learner was not saved: " + id));
    }

    private static String requiredId(String id, String label) {
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidSetupException(label + " is required.");
        }
        return id.trim();
    }

    private static Set<String> normalizeCourseIds(Set<String> courseIds) {
        if (courseIds == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String courseId : courseIds) {
            if (courseId == null || courseId.trim().isEmpty()) {
                throw new InvalidSetupException("Course ids must be non-blank.");
            }
            normalized.add(courseId.trim());
        }
        return normalized;
    }
}
