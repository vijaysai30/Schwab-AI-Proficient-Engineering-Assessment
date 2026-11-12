package com.waitlistservice.waitlist.domain;

public enum RegistrationOutcome {
    ENROLLED,
    WAITLISTED,
    ALREADY_ENROLLED,
    ALREADY_WAITLISTED,
    PREREQUISITE_NOT_MET,
    DROPPED,
    NOT_ENROLLED,
    INVALID_REQUEST,
    NO_PROMOTION
}
