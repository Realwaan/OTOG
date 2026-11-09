package com.otog.exception;

/**
 * Thrown when a requested entity (User, Wallet, Transaction, etc.) cannot be found.
 */
public class EntityNotFoundException extends OtogException {
    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entityType, String id) {
        super(String.format("%s with identifier '%s' was not found", entityType, id));
    }
}
