package com.waitlistservice.waitlist.api;

import com.waitlistservice.waitlist.domain.LearnerView;
import com.waitlistservice.waitlist.domain.OfferingView;
import com.waitlistservice.waitlist.domain.UnknownResourceException;
import com.waitlistservice.waitlist.service.CourseSetupService;
import com.waitlistservice.waitlist.service.EnrollmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
public class CourseSetupController {

    private final CourseSetupService courseSetupService;
    private final EnrollmentService enrollmentService;

    public CourseSetupController(CourseSetupService courseSetupService, EnrollmentService enrollmentService) {
        this.courseSetupService = courseSetupService;
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/api/offerings")
    public ResponseEntity<OfferingView> createOffering(@RequestBody CreateOfferingRequest request) {
        courseSetupService.createOffering(
                request == null ? null : request.id(),
                request == null ? null : request.capacity(),
                request == null ? null : request.prerequisiteCourseIds()
        );
        OfferingView offering = enrollmentService.getOffering(request.id().trim())
                .orElseThrow(() -> new UnknownResourceException("Offering was not saved."));
        return ResponseEntity.status(HttpStatus.CREATED).body(offering);
    }

    @GetMapping("/api/offerings/{offeringId}")
    public OfferingView getOffering(@PathVariable String offeringId) {
        return enrollmentService.getOffering(offeringId)
                .orElseThrow(() -> new UnknownResourceException("Offering does not exist: " + offeringId));
    }

    @PostMapping("/api/learners")
    public ResponseEntity<LearnerView> createLearner(@RequestBody CreateLearnerRequest request) {
        LearnerView learner = courseSetupService.createLearner(
                request == null ? null : request.id(),
                request == null ? null : request.completedCourseIds()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(learner);
    }

    @PutMapping("/api/learners/{learnerId}/completions")
    public LearnerView replaceCompletions(
            @PathVariable String learnerId,
            @RequestBody CompletionRequest request
    ) {
        return courseSetupService.replaceCompletions(
                learnerId,
                request == null ? null : request.completedCourseIds()
        );
    }

    public record CreateOfferingRequest(String id, Integer capacity, Set<String> prerequisiteCourseIds) {
    }

    public record CreateLearnerRequest(String id, Set<String> completedCourseIds) {
    }

    public record CompletionRequest(Set<String> completedCourseIds) {
    }
}
