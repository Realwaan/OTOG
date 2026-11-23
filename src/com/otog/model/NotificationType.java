package com.otog.model;

/**
 * Classifies persistent in-app notifications emitted after committed financial events.
 */
public enum NotificationType {
    TRANSFER_SENT,
    TRANSFER_RECEIVED,
    CASH_IN_COMPLETED,
    CASH_IN_PENDING,
    CASH_IN_FAILED,
    CASH_OUT_COMPLETED,
    CASH_OUT_PENDING,
    CASH_OUT_FAILED,
    PAYMENT_UPDATE
}
