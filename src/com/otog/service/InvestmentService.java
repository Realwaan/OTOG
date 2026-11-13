package com.otog.service;

import com.otog.model.InvestMoneyTransaction;
import com.otog.model.Transaction;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared service contract for simulated investment subscriptions, redemptions, and return estimates.
 */
public interface InvestmentService {
    InvestMoneyTransaction invest(String referenceNumber, String walletId, String product,
                                  BigDecimal amount, BigDecimal units);

    Transaction withdrawInvestment(String referenceNumber, String walletId, String investmentTransactionId);

    BigDecimal calculateReturns(String investmentTransactionId);

    List<InvestMoneyTransaction> getInvestmentsByWalletId(String walletId);
}
