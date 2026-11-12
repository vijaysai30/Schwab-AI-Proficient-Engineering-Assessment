package com.waitlistservice.waitlist.domain;

public class InvalidSetupException extends RuntimeException {

    public InvalidSetupException(String message) {
        super(message);
    }
}
