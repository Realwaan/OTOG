package com.otog.service;

/**
 * Status outcomes returned by mock and external banking payment providers.
 */
public enum BankResponseStatus {
    SUCCESS,
    REJECTED,
    FAILED,
    TIMEOUT,
    PENDING,
    UNKNOWN;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isTerminalFailure() {
        return this == REJECTED || this == FAILED;
    }

    public boolean isUncertain() {
        return this == TIMEOUT || this == PENDING || this == UNKNOWN;
    }
}
