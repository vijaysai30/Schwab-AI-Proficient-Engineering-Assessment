package com.waitlistservice.waitlist.api;

import com.waitlistservice.waitlist.domain.RegistrationOutcome;
import com.waitlistservice.waitlist.domain.RegistrationResult;
import com.waitlistservice.waitlist.service.EnrollmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/api/offerings/{offeringId}/enrollments")
    public ResponseEntity<RegistrationResult> enroll(
            @PathVariable String offeringId,
            @RequestBody(required = false) EnrollRequest request
    ) {
        RegistrationResult result = enrollmentService.enroll(
                request == null ? null : request.learnerId(),
                offeringId
        );
        return ResponseEntity.status(httpStatus(result.outcome())).body(result);
    }

    @DeleteMapping("/api/offerings/{offeringId}/enrollments/{learnerId}")
    public ResponseEntity<RegistrationResult> drop(
            @PathVariable String offeringId,
            @PathVariable String learnerId
    ) {
        RegistrationResult result = enrollmentService.drop(learnerId, offeringId);
        return ResponseEntity.status(httpStatus(result.outcome())).body(result);
    }

    @PostMapping("/api/offerings/{offeringId}/promotions")
    public ResponseEntity<RegistrationResult> promote(@PathVariable String offeringId) {
        RegistrationResult result = enrollmentService.promoteNextEligible(offeringId);
        return ResponseEntity.status(httpStatus(result.outcome())).body(result);
    }

    public record EnrollRequest(String learnerId) {
    }

    static HttpStatus httpStatus(RegistrationOutcome outcome) {
        if (outcome == RegistrationOutcome.INVALID_REQUEST) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.OK;
    }
}
