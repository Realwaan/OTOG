package com.otog.service;

import java.time.LocalDateTime;

/**
 * Encapsulates the response from a banking API or mock payment provider.
 */
public class BankApiResponse {
    private final BankResponseStatus status;
    private final String providerReference;
    private final String responseCode;
    private final String message;
    private final LocalDateTime timestamp;

    public BankApiResponse(BankResponseStatus status, String providerReference, String responseCode, String message) {
        this.status = status;
        this.providerReference = providerReference;
        this.responseCode = responseCode;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public BankResponseStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public boolean isSuccess() {
        return status != null && status.isSuccess();
    }

    public boolean isFailure() {
        return status != null && status.isTerminalFailure();
    }

    public boolean isTimeout() {
        return status == BankResponseStatus.TIMEOUT;
    }

    public boolean isPending() {
        return status != null && status.isUncertain();
    }

    public boolean isUnknown() {
        return status == BankResponseStatus.UNKNOWN;
    }

    public static BankApiResponse success(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.SUCCESS, providerRef, "200", message);
    }

    public static BankApiResponse rejected(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.REJECTED, providerRef, "400", message);
    }

    public static BankApiResponse failed(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.FAILED, providerRef, "500", message);
    }

    public static BankApiResponse timeout(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.TIMEOUT, providerRef, "504", message);
    }

    public static BankApiResponse pending(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.PENDING, providerRef, "102", message);
    }

    public static BankApiResponse unknown(String providerRef, String message) {
        return new BankApiResponse(BankResponseStatus.UNKNOWN, providerRef, "102", message);
    }

    @Override
    public String toString() {
        return "BankApiResponse{" +
                "status=" + status +
                ", providerReference='" + providerReference + '\'' +
                ", responseCode='" + responseCode + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
