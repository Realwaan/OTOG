package com.otog.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a payment method (e.g. linked bank account or card) owned by a user.
 */
public class PaymentMethod {
    private String paymentMethodId;
    private String userId;
    private PaymentMethodType type;
    private String providerName;
    private String accountName;
    private String accountNumber;
    private boolean isDefault;
    private PaymentMethodStatus status;
    private LocalDateTime createdAt;

    public PaymentMethod() {
        this.type = PaymentMethodType.BANK_ACCOUNT;
        this.status = PaymentMethodStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public PaymentMethod(String paymentMethodId, String userId, PaymentMethodType type,
                         String providerName, String accountName, String accountNumber) {
        this();
        this.paymentMethodId = paymentMethodId;
        this.userId = userId;
        this.type = type;
        this.providerName = providerName;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public void setPaymentMethodId(String paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public PaymentMethodType getType() {
        return type;
    }

    public void setType(PaymentMethodType type) {
        this.type = type;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public PaymentMethodStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentMethodStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentMethod that)) return false;
        return Objects.equals(paymentMethodId, that.paymentMethodId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(paymentMethodId);
    }

    @Override
    public String toString() {
        return "PaymentMethod{" +
                "paymentMethodId='" + paymentMethodId + '\'' +
                ", userId='" + userId + '\'' +
                ", providerName='" + providerName + '\'' +
                ", type=" + type +
                ", isDefault=" + isDefault +
                '}';
    }
}
