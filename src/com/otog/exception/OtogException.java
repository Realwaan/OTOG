package com.otog.exception;

/**
 * Base unchecked exception for all OTOG domain and transaction errors.
 */
public class OtogException extends RuntimeException {
    public OtogException(String message) {
        super(message);
    }

    public OtogException(String message, Throwable cause) {
        super(message, cause);
    }
}
