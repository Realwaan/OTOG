package com.otog.service;

import com.otog.model.User;

import java.util.Optional;

/**
 * Shared service contract for authentication, 2FA, and PIN verification workflows.
 */
public interface AuthService {
    Optional<User> authenticatePassword(String usernameOrMobile, String rawPassword);

    String issueTwoFactorOtp(String userId);

    boolean verifyTwoFactorOtp(String userId, String otpCode);

    boolean verifyPin(String userId, String rawPin);

    void logout(String userId);
}
