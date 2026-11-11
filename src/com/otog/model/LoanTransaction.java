package com.otog.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Specialized transaction for loans.
 * Matches Loan in class diagram.
 */
public class LoanTransaction extends Transaction {
    private BigDecimal principal;
    private BigDecimal interestRate;
    private int termMonths;
    private LocalDate dueDate;

    public LoanTransaction() {
        super();
        setType(TransactionType.LOAN_DISBURSEMENT);
    }

    public LoanTransaction(String transactionId, String referenceNumber, String walletId,
                           BigDecimal principal, BigDecimal interestRate, int termMonths, LocalDate dueDate) {
        super(transactionId, referenceNumber, null, walletId, principal, BigDecimal.ZERO,
                TransactionType.LOAN_DISBURSEMENT, "Loan disbursement of " + principal);
        this.principal = principal;
        this.interestRate = interestRate;
        this.termMonths = termMonths;
        this.dueDate = dueDate;
    }

    public BigDecimal getPrincipal() {
        return principal;
    }

    public void setPrincipal(BigDecimal principal) {
        this.principal = principal;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public void setTermMonths(int termMonths) {
        this.termMonths = termMonths;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public void apply() {
        setStatus(TransactionStatus.PENDING);
    }

    public void approve() {
        execute();
    }

    public void repay() {
        setType(TransactionType.LOAN_REPAYMENT);
        execute();
    }
}
