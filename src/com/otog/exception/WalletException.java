package com.otog.exception;

/**
 * Thrown for wallet state or lifecycle errors (e.g. frozen wallet, inactive user).
 */
public class WalletException extends OtogException {
    public WalletException(String message) {
        super(message);
    }

    public WalletException(String message, Throwable cause) {
        super(message, cause);
    }
}
