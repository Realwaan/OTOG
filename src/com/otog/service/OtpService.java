package com.otog.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe in-memory OTP manager enforcing account binding, expiry,
 * one-time consumption, resend cooldown, and failed-attempt limits.
 * Delivery is simulated via stdout and surfaced for UI notification.
 */
public class OtpService {
    public static final int OTP_EXPIRY_MINUTES = 5;
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    public static final int MAX_ATTEMPTS = 5;
    public static final int OTP_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ConcurrentHashMap<String, OtpRecord> store = new ConcurrentHashMap<>();

    public OtpService() {
        this(Clock.systemUTC());
    }

    public OtpService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Generates a 6-digit OTP bound to the given userId and prints a simulated SMS notice.
     * Enforces resend cooldown regardless of whether the prior code was used or exhausted.
     *
     * @param userId account identifier to bind the OTP to
     * @return generated 6-digit OTP code
     * @throws IllegalStateException if invoked within the resend cooldown window
     */
    public String generateOtp(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId must not be null or empty");
        }

        final String boundUserId = userId.trim();
        final Instant now = clock.instant();
        final AtomicReference<String> issuedCode = new AtomicReference<>();

        store.compute(boundUserId, (key, existing) -> {
            if (existing != null) {
                long secondsSinceIssue = Duration.between(existing.getCreatedAt(), now).getSeconds();
                if (secondsSinceIssue < RESEND_COOLDOWN_SECONDS) {
                    long waitSeconds = RESEND_COOLDOWN_SECONDS - Math.max(0L, secondsSinceIssue);
                    throw new IllegalStateException(
                            "Resend cooldown active. Please wait " + waitSeconds + " seconds.");
                }
            }
            String code = generateRandomCode();
            issuedCode.set(code);
            return new OtpRecord(code, boundUserId, now, false, 0);
        });

        String code = issuedCode.get();
        System.out.println("[SIMULATED SMS] OTP for " + boundUserId + ": " + code
                + " (expires in " + OTP_EXPIRY_MINUTES + " minutes)");
        return code;
    }

    /**
     * Atomically verifies an OTP code for the given account.
     * Rejects random/wrong codes, expired codes, already-used codes,
     * codes issued to another account, and records with exhausted attempts.
     */
    public boolean verifyOtp(String userId, String code) {
        if (userId == null || userId.trim().isEmpty() || code == null || code.trim().isEmpty()) {
            return false;
        }

        final String boundUserId = userId.trim();
        final String cleanCode = code.trim();
        final Instant now = clock.instant();
        final AtomicBoolean verified = new AtomicBoolean(false);

        store.compute(boundUserId, (key, record) -> {
            if (record == null || record.isUsed() || record.getFailedAttempts() >= MAX_ATTEMPTS) {
                return record;
            }

            if (Duration.between(record.getCreatedAt(), now).compareTo(Duration.ofMinutes(OTP_EXPIRY_MINUTES)) >= 0) {
                return new OtpRecord(record.getCode(), record.getUserId(), record.getCreatedAt(),
                        true, record.getFailedAttempts());
            }

            if (!record.getUserId().equals(boundUserId)) {
                return record;
            }

            if (!record.getCode().equals(cleanCode)) {
                int newAttempts = record.getFailedAttempts() + 1;
                boolean exhausted = newAttempts >= MAX_ATTEMPTS;
                return new OtpRecord(record.getCode(), record.getUserId(), record.getCreatedAt(),
                        exhausted, newAttempts);
            }

            verified.set(true);
            return new OtpRecord(record.getCode(), record.getUserId(), record.getCreatedAt(),
                    true, record.getFailedAttempts());
        });

        return verified.get();
    }

    public int getRemainingAttempts(String userId) {
        if (userId == null) {
            return 0;
        }
        OtpRecord record = store.get(userId.trim());
        if (record == null || record.isUsed()) {
            return 0;
        }
        return Math.max(0, MAX_ATTEMPTS - record.getFailedAttempts());
    }

    public OtpRecord getRecord(String userId) {
        return userId == null ? null : store.get(userId.trim());
    }

    public void invalidateOtp(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return;
        }
        store.computeIfPresent(userId.trim(), (k, record) -> new OtpRecord(
                record.getCode(), record.getUserId(), record.getCreatedAt(), true, record.getFailedAttempts()
        ));
    }

    public void clear() {
        store.clear();
    }

    private String generateRandomCode() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int code = random.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", code);
    }

    public static final class OtpRecord {
        private final String code;
        private final String userId;
        private final Instant createdAt;
        private final boolean used;
        private final int failedAttempts;

        public OtpRecord(String code, String userId, Instant createdAt, boolean used, int failedAttempts) {
            this.code = code;
            this.userId = userId;
            this.createdAt = createdAt;
            this.used = used;
            this.failedAttempts = failedAttempts;
        }

        public String getCode() {
            return code;
        }

        public String getUserId() {
            return userId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public boolean isUsed() {
            return used;
        }

        public int getFailedAttempts() {
            return failedAttempts;
        }
    }
}
