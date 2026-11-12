package com.waitlistservice.waitlist.domain;

public record RegistrationResult(
        RegistrationOutcome outcome,
        String learnerId,
        String offeringId,
        String promotedLearnerId,
        String message
) {
    public static RegistrationResult of(
            RegistrationOutcome outcome,
            String learnerId,
            String offeringId,
            String message
    ) {
        return new RegistrationResult(outcome, learnerId, offeringId, null, message);
    }

    public static RegistrationResult dropped(
            String learnerId,
            String offeringId,
            String promotedLearnerId
    ) {
        String message = promotedLearnerId == null
                ? "Learner dropped. The seat is available."
                : "Learner dropped. Promoted the earliest eligible waitlisted learner.";
        return new RegistrationResult(
                RegistrationOutcome.DROPPED,
                learnerId,
                offeringId,
                promotedLearnerId,
                message
        );
    }
}
