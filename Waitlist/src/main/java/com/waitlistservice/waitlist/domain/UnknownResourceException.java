package com.waitlistservice.waitlist.domain;

public class UnknownResourceException extends RuntimeException {

    public UnknownResourceException(String message) {
        super(message);
    }
}
