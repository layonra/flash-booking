package com.cielo.booking.exception;

public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(code, message);
    }
}
