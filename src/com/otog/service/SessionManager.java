package com.otog.service;

import com.otog.exception.WalletException;
import com.otog.model.User;
import com.otog.model.Wallet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Thread-safe Singleton managing the active authenticated user session,
 * inactivity expiration, PIN attempt limits, and payment authorization state.
 */
public class SessionManager {
    public static final int MAX_PIN_ATTEMPTS = 3;
    public static final long INACTIVITY_TIMEOUT_MINUTES = 15L;

    private static volatile SessionManager instance;

    private Clock clock = Clock.systemUTC();
    private long inactivityTimeoutMillis = INACTIVITY_TIMEOUT_MINUTES * 60_000L;

    private User currentUser;
    private Wallet currentWallet;
    private long loginTimestamp;
    private Instant lastActivityAt;
    private boolean pinVerified;
    private int failedPinAttempts;
    private boolean expiredDueToInactivity;
    private boolean lockedOutByPin;

    private SessionManager() {
    }

    public static SessionManager getInstance() {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager();
                }
            }
        }
        return instance;
    }

    public synchronized void login(User user, Wallet wallet) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null when establishing a session");
        }
        this.currentUser = user;
        this.currentWallet = wallet;
        Instant now = clock.instant();
        this.loginTimestamp = now.toEpochMilli();
        this.lastActivityAt = now;
        this.pinVerified = false;
        this.failedPinAttempts = 0;
        this.expiredDueToInactivity = false;
        this.lockedOutByPin = false;
        user.login();
    }

    public synchronized void logout() {
        clearSessionState(false, false);
    }

    private void clearSessionState(boolean expired, boolean pinLocked) {
        if (this.currentUser != null) {
            this.currentUser.logout();
        }
        this.currentUser = null;
        this.currentWallet = null;
        this.loginTimestamp = 0;
        this.lastActivityAt = null;
        this.pinVerified = false;
        this.failedPinAttempts = 0;
        this.expiredDueToInactivity = expired;
        this.lockedOutByPin = pinLocked;
    }

    private boolean checkAndExpireIfTimedOut() {
        if (currentUser == null || lastActivityAt == null) {
            return false;
        }
        long elapsedMillis = Duration.between(lastActivityAt, clock.instant()).toMillis();
        if (elapsedMillis >= inactivityTimeoutMillis) {
            clearSessionState(true, false);
            return true;
        }
        return false;
    }

    public synchronized boolean isLoggedIn() {
        checkAndExpireIfTimedOut();
        return currentUser != null;
    }

    public synchronized boolean isSessionActive(String userId) {
        if (userId == null || !isLoggedIn()) {
            return false;
        }
        return userId.equals(currentUser.getUserId());
    }

    public synchronized boolean touchActivity() {
        if (!isLoggedIn()) {
            return false;
        }
        this.lastActivityAt = clock.instant();
        return true;
    }

    public synchronized User getCurrentUser() {
        if (!isLoggedIn()) {
            return null;
        }
        return currentUser;
    }

    public synchronized Wallet getCurrentWallet() {
        if (!isLoggedIn()) {
            return null;
        }
        return currentWallet;
    }

    public synchronized void setCurrentWallet(Wallet wallet) {
        if (isLoggedIn()) {
            this.currentWallet = wallet;
            touchActivity();
        }
    }

    public synchronized Wallet refreshWallet(WalletOperations walletService) {
        requireAuthentication();
        if (walletService == null) {
            throw new IllegalArgumentException("WalletOperations service must not be null");
        }
        Wallet updated = (this.currentWallet != null && this.currentWallet.getWalletId() != null)
                ? walletService.getWallet(this.currentWallet.getWalletId())
                : walletService.getWalletByUserId(this.currentUser.getUserId());
        this.currentWallet = updated;
        return updated;
    }

    public synchronized long getLoginTimestamp() {
        return loginTimestamp;
    }

    public synchronized Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public synchronized void requireAuthentication() {
        if (!isLoggedIn()) {
            if (expiredDueToInactivity) {
                throw new SecurityException("Session expired due to inactivity. Please log in again.");
            }
            if (lockedOutByPin) {
                throw new SecurityException("Session terminated after maximum failed PIN attempts.");
            }
            throw new SecurityException("Access denied: active authenticated session required.");
        }
    }

    public synchronized void requireActiveSessionFor(String userId) {
        requireAuthentication();
        if (userId == null || !userId.equals(currentUser.getUserId())) {
            throw new SecurityException("Access denied: session belongs to a different user.");
        }
    }

    public synchronized void markPinVerified() {
        requireAuthentication();
        this.failedPinAttempts = 0;
        this.pinVerified = true;
        this.lastActivityAt = clock.instant();
    }

    public synchronized boolean isPinVerified() {
        return isLoggedIn() && pinVerified;
    }

    public synchronized void clearPinAuthorization() {
        this.pinVerified = false;
    }

    public synchronized void resetPinAttempts() {
        this.pinVerified = false;
        this.failedPinAttempts = 0;
        this.lockedOutByPin = false;
    }

    /**
     * Records a failed PIN attempt on the current session.
     * Terminates the session once {@link #MAX_PIN_ATTEMPTS} is reached.
     *
     * @return true if the session was terminated due to reaching the PIN attempt limit
     */
    public synchronized boolean recordFailedPinAttempt() {
        if (!isLoggedIn()) {
            return false;
        }
        this.pinVerified = false;
        this.failedPinAttempts++;
        if (this.failedPinAttempts >= MAX_PIN_ATTEMPTS) {
            clearSessionState(false, true);
            return true;
        }
        this.lastActivityAt = clock.instant();
        return false;
    }

    public synchronized int getFailedPinAttempts() {
        return failedPinAttempts;
    }

    public synchronized boolean isExpiredDueToInactivity() {
        checkAndExpireIfTimedOut();
        return expiredDueToInactivity;
    }

    public synchronized boolean isLockedOutByPin() {
        return lockedOutByPin;
    }

    public synchronized void lockOutByPin() {
        clearSessionState(false, true);
    }

    public synchronized void requirePinAuthorization(String expectedUserId) {
        checkAndExpireIfTimedOut();
        if (expiredDueToInactivity) {
            throw new WalletException("Session expired due to inactivity. Please log in again.");
        }
        if (lockedOutByPin) {
            throw new WalletException("Session locked after excessive failed PIN attempts.");
        }
        if (currentUser == null) {
            throw new WalletException("Active authenticated session required for outgoing payments.");
        }
        if (expectedUserId != null && !expectedUserId.equals(currentUser.getUserId())) {
            throw new WalletException("Wallet ownership mismatch: active session user cannot authorize this wallet.");
        }
        if (!pinVerified) {
            throw new WalletException("Transaction PIN authorization required for outgoing payments.");
        }
        this.lastActivityAt = clock.instant();
    }

    public synchronized void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public synchronized void setInactivityTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Inactivity timeout must be positive");
        }
        this.inactivityTimeoutMillis = timeout.toMillis();
    }

    public synchronized void reset() {
        clearSessionState(false, false);
        this.clock = Clock.systemUTC();
        this.inactivityTimeoutMillis = INACTIVITY_TIMEOUT_MINUTES * 60_000L;
    }
}
