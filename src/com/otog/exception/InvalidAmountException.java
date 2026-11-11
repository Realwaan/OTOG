package com.otog.exception;

/**
 * Thrown when an invalid monetary amount is supplied (e.g. null, negative, zero, or scale > 2).
 */
public class InvalidAmountException extends OtogException {
    public InvalidAmountException(String message) {
        super(message);
    }

    public InvalidAmountException(String message, Throwable cause) {
        super(message, cause);
    }
}
