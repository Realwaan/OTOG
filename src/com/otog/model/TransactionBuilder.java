package com.otog.model;

import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Fluent builder for creating {@link SendMoneyTransaction} instances with validation.
 * Supports Creational Builder pattern for transaction construction.
 */
public class TransactionBuilder {
    private String transactionId;
    private String referenceNumber;
    private String sourceWalletId;
    private String targetWalletId;
    private BigDecimal amount;
    private BigDecimal fee;
    private String recipientNumber;
    private String note;
    private TransactionType type = TransactionType.SEND_MONEY;
    private TransactionStatus status = TransactionStatus.PENDING;

    public TransactionBuilder() {
    }

    public TransactionBuilder transactionId(String transactionId) {
        this.transactionId = transactionId;
        return this;
    }

    public TransactionBuilder referenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
        return this;
    }

    public TransactionBuilder from(String sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
        return this;
    }

    public TransactionBuilder sourceWalletId(String sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
        return this;
    }

    public TransactionBuilder to(String targetWalletId) {
        this.targetWalletId = targetWalletId;
        return this;
    }

    public TransactionBuilder targetWalletId(String targetWalletId) {
        this.targetWalletId = targetWalletId;
        return this;
    }

    public TransactionBuilder amount(BigDecimal amount) {
        this.amount = amount;
        return this;
    }

    public TransactionBuilder fee(BigDecimal fee) {
        this.fee = fee;
        return this;
    }

    public TransactionBuilder recipientNumber(String recipientNumber) {
        this.recipientNumber = recipientNumber;
        return this;
    }

    public TransactionBuilder note(String note) {
        this.note = note;
        return this;
    }

    public TransactionBuilder description(String description) {
        this.note = description;
        return this;
    }

    public TransactionBuilder type(TransactionType type) {
        this.type = type;
        return this;
    }

    public TransactionBuilder status(TransactionStatus status) {
        this.status = status;
        return this;
    }

    /**
     * Builds and validates a SendMoneyTransaction.
     *
     * @return the newly constructed SendMoneyTransaction
     * @throws IllegalStateException if required fields are missing
     */
    public SendMoneyTransaction build() {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalStateException("Reference number is required for transaction construction");
        }
        if (sourceWalletId == null || sourceWalletId.trim().isEmpty()) {
            throw new IllegalStateException("Source wallet ID (from) is required for transaction construction");
        }
        if (targetWalletId == null || targetWalletId.trim().isEmpty()) {
            throw new IllegalStateException("Target wallet ID (to) is required for transaction construction");
        }
        if (amount == null) {
            throw new IllegalStateException("Amount is required for transaction construction");
        }

        String effectiveTxnId = (transactionId != null && !transactionId.trim().isEmpty())
                ? transactionId.trim()
                : IdGenerator.nextTransactionId();

        BigDecimal effectiveFee = (fee != null) ? fee : MoneyUtil.ZERO;
        String effectiveNote = (note != null) ? note : "Transfer to " + (recipientNumber != null ? recipientNumber : targetWalletId);

        SendMoneyTransaction txn = new SendMoneyTransaction(
                effectiveTxnId,
                referenceNumber.trim(),
                sourceWalletId.trim(),
                targetWalletId.trim(),
                amount,
                effectiveFee,
                recipientNumber,
                effectiveNote
        );

        if (type != null) {
            txn.setType(type);
        }
        if (status != null) {
            txn.setStatus(status);
        }

        return txn;
    }
}

