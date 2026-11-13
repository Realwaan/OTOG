package com.otog.service;

import com.otog.model.LoanTransaction;
import com.otog.model.Transaction;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared service contract for simulated loan applications, approvals, and repayments.
 */
public interface LoanService {
    LoanTransaction applyLoan(String referenceNumber, String walletId, BigDecimal principal,
                              BigDecimal interestRate, int termMonths);

    LoanTransaction approveLoan(String loanTransactionId);

    Transaction repayLoan(String referenceNumber, String walletId, String loanTransactionId, BigDecimal amount);

    List<LoanTransaction> getLoansByWalletId(String walletId);
}
