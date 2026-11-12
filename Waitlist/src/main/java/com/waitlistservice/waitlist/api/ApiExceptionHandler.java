package com.waitlistservice.waitlist.api;

import com.waitlistservice.waitlist.domain.AlreadyExistsException;
import com.waitlistservice.waitlist.domain.InvalidSetupException;
import com.waitlistservice.waitlist.domain.UnknownResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidSetupException.class)
    public ResponseEntity<Map<String, String>> invalidSetup(InvalidSetupException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> alreadyExists(AlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(UnknownResourceException.class)
    public ResponseEntity<Map<String, String>> unknown(UnknownResourceException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }
}
