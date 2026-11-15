package com.otog.model;

import java.time.LocalDateTime;

public class Credential {
    private String userId;
    private String passwordHash;
    private String salt;
    private int failedAttempts;
    private LocalDateTime lockedUntil;
    private LocalDateTime updatedAt;

    public Credential() {
        this.failedAttempts = 0;
        this.updatedAt = LocalDateTime.now();
    }

    public Credential(String userId, String passwordHash, String salt) {
        this(userId, passwordHash, salt, 0, null, LocalDateTime.now());
    }

    public Credential(String userId, String passwordHash, String salt,
                      int failedAttempts, LocalDateTime lockedUntil, LocalDateTime updatedAt) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
