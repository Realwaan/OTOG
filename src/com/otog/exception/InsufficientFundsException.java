package com.otog.exception;

import java.math.BigDecimal;

/**
 * Thrown when an account or wallet has insufficient funds to cover a debit or payment.
 */
public class InsufficientFundsException extends OtogException {
    private final BigDecimal availableBalance;
    private final BigDecimal requiredAmount;

    public InsufficientFundsException(String message) {
        super(message);
        this.availableBalance = null;
        this.requiredAmount = null;
    }

    public InsufficientFundsException(String walletId, BigDecimal availableBalance, BigDecimal requiredAmount) {
        super(String.format("Insufficient funds for wallet '%s'. Available: %s, Required: %s",
                walletId, availableBalance, requiredAmount));
        this.availableBalance = availableBalance;
        this.requiredAmount = requiredAmount;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getRequiredAmount() {
        return requiredAmount;
    }
}
