package com.otog.service;

import com.otog.model.User;
import com.otog.model.Wallet;

public class SessionManager {
    private static volatile SessionManager instance;

    private User currentUser;
    private Wallet currentWallet;
    private long loginTimestamp;

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
        this.loginTimestamp = System.currentTimeMillis();
        user.login();
    }

    public synchronized void logout() {
        if (this.currentUser != null) {
            this.currentUser.logout();
        }
        this.currentUser = null;
        this.currentWallet = null;
        this.loginTimestamp = 0;
    }

    public synchronized boolean isLoggedIn() {
        return currentUser != null;
    }

    public synchronized User getCurrentUser() {
        return currentUser;
    }

    public synchronized Wallet getCurrentWallet() {
        return currentWallet;
    }

    public synchronized void setCurrentWallet(Wallet wallet) {
        this.currentWallet = wallet;
    }

    public synchronized long getLoginTimestamp() {
        return loginTimestamp;
    }

    public synchronized void requireAuthentication() {
        if (!isLoggedIn()) {
            throw new SecurityException("Access denied: active authenticated session required.");
        }
    }

    public synchronized void reset() {
        logout();
    }
}
