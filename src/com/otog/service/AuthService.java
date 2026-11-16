package com.otog.service;

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
}
