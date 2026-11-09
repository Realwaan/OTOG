package com.otog.exception;

/**
 * Thrown when an idempotent transaction reference has already been executed or recorded.
 */
public class DuplicateTransactionException extends OtogException {
    private final String referenceNumber;

    public DuplicateTransactionException(String message) {
        super(message);
        this.referenceNumber = null;
    }

    public DuplicateTransactionException(String referenceNumber, String message) {
        super(message);
        this.referenceNumber = referenceNumber;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }
}
