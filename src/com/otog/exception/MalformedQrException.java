package com.otog.exception;

/**
 * Thrown when a merchant QR code payload or image file is malformed, corrupted,
 * contains an invalid checksum, or is missing required project fields.
 */
public class MalformedQrException extends WalletException {

    public MalformedQrException(String message) {
        super(message);
    }

    public MalformedQrException(String message, Throwable cause) {
        super(message, cause);
    }
}
