package com.otog.service;

import com.otog.exception.WalletException;
import com.otog.model.User;

import java.util.Optional;

/**
 * Shared service contract for authentication, 2FA, and PIN verification workflows.
 */
public interface AuthService {
    Optional<User> authenticatePassword(String usernameOrMobile, String rawPassword);

    User registerUser(String name, String mobileNumber, String email, String rawPassword);

    User registerUser(String name, String mobileNumber, String email, String rawPassword, com.otog.model.UserRole role);

    String issueTwoFactorOtp(String userId);

    boolean verifyTwoFactorOtp(String userId, String otpCode);

    boolean verifyPin(String userId, String rawPin);

    boolean hasPin(String userId);

    void setPin(String userId, String currentPassword, String newPin);

    void changePin(String userId, String currentPin, String newPin);

    void logout(String userId);

    /**
     * Verifies a transaction PIN and throws a domain {@link WalletException} on mismatch or lockout.
     */
    static void verifyPinOrThrow(AuthService authService, String userId, String rawPin) {
        try {
            if (!authService.verifyPin(userId, rawPin)) {
                SessionManager session = SessionManager.getInstance();
                if (session.isLockedOutByPin()) {
                    throw new WalletException("Session terminated after maximum failed PIN attempts.");
                }
                throw new WalletException("Invalid transaction PIN. Outgoing payment blocked.");
            }
        } catch (SecurityException ex) {
            throw new WalletException(ex.getMessage(), ex);
        }
    }
}
