package com.otog.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory OTP manager with account binding, expiry, one-time use,
 * resend cooldown, and attempt limits.
 * Delivery is simulated (logged to System.out).
 */
public class OtpService {
    public static final int OTP_EXPIRY_MINUTES = 5;
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    public static final int MAX_ATTEMPTS = 5;
    private static final int OTP_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, OtpRecord> store = new ConcurrentHashMap<>();

    /**
     * Generates a 6-digit OTP bound to the given userId, prints it as a simulated SMS,
     * and returns the code. Enforces resend cooldown.
     *
     * @param userId the account to issue the OTP for
     * @return the generated OTP code
     * @throws IllegalStateException if called within the resend cooldown window
     */
    public String generateOtp(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId must not be null or empty");
        }

        OtpRecord existing = store.get(userId);
        if (existing != null && !existing.used) {
            long secondsSinceIssue = Instant.now().getEpochSecond() - existing.createdAt.getEpochSecond();
            if (secondsSinceIssue < RESEND_COOLDOWN_SECONDS) {
                throw new IllegalStateException(
                        "Resend cooldown active. Please wait " +
                                (RESEND_COOLDOWN_SECONDS - secondsSinceIssue) + " seconds.");
            }
        }

        String code = generateRandomCode();
        OtpRecord record = new OtpRecord(code, userId, Instant.now(), false, 0);
        store.put(userId, record);

        System.out.println("[SIMULATED SMS] OTP for " + userId + ": " + code);
        return code;
    }

    /**
     * Verifies the supplied OTP code for the given userId.
     * Rejects random/wrong codes, expired codes, already-used codes,
     * codes bound to a different account, and codes after max attempts are exhausted.
     *
     * @param userId the account attempting verification
     * @param code   the OTP code entered by the user
     * @return true if the code is valid and consumed; false otherwise
     */
    public boolean verifyOtp(String userId, String code) {
        if (userId == null || code == null) {
            return false;
        }

        OtpRecord record = store.get(userId);
        if (record == null) {
            return false;
        }

        // Already used
        if (record.used) {
            return false;
        }

        // Attempts exhausted
        if (record.failedAttempts >= MAX_ATTEMPTS) {
            return false;
        }

        // Expired
        long secondsElapsed = Instant.now().getEpochSecond() - record.createdAt.getEpochSecond();
        if (secondsElapsed > OTP_EXPIRY_MINUTES * 60L) {
            return false;
        }

        // Account binding — the record's userId must match
        if (!record.userId.equals(userId)) {
            return false;
        }

        // Code match
        if (!record.code.equals(code)) {
            // Wrong code — increment failed attempts
            int newAttempts = record.failedAttempts + 1;
            store.put(userId, new OtpRecord(
                    record.code, record.userId, record.createdAt, record.used, newAttempts));
            if (newAttempts >= MAX_ATTEMPTS) {
                // Invalidate the record after max attempts
                store.put(userId, new OtpRecord(
                        record.code, record.userId, record.createdAt, true, newAttempts));
            }
            return false;
        }

        // Success — mark as used (one-time use)
        store.put(userId, new OtpRecord(
                record.code, record.userId, record.createdAt, true, record.failedAttempts));
        return true;
    }

    /**
     * Returns the current OTP record for a userId (for testing purposes).
     */
    public OtpRecord getRecord(String userId) {
        return store.get(userId);
    }

    /**
     * Clears all OTP records (useful in tests).
     */
    public void clear() {
        store.clear();
    }

    private String generateRandomCode() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int code = random.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", code);
    }

    // ---- inner record ----

    public static class OtpRecord {
        public final String code;
        public final String userId;
        public final Instant createdAt;
        public final boolean used;
        public final int failedAttempts;

        public OtpRecord(String code, String userId, Instant createdAt, boolean used, int failedAttempts) {
            this.code = code;
            this.userId = userId;
            this.createdAt = createdAt;
            this.used = used;
            this.failedAttempts = failedAttempts;
        }
    }
}
