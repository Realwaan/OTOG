package com.otog.model;

/**
 * Lifecycle states for a simulated OTOG loan application (CAP-12).
 * Single disbursement upon admin approval; full lump-sum repayment transitions to PAID.
 */
public enum LoanStatus {
    PENDING_APPROVAL,
    DISBURSED,
    PAID,
    REJECTED
}
