package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Represents a financial transaction within the OTOG E-Wallet platform.
 * Supports idempotency via unique referenceNumber and maintains BigDecimal financial precision.
 */
public class Transaction {
    private String transactionId;
    private String referenceNumber;
    private String sourceWalletId;
    private String targetWalletId;
    private BigDecimal amount;
    private BigDecimal fee;
    private BigDecimal totalAmount;
    private String currency;
    private TransactionType type;
    private TransactionStatus status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public Transaction() {
        this.amount = MoneyUtil.ZERO;
        this.fee = MoneyUtil.ZERO;
        this.totalAmount = MoneyUtil.ZERO;
        this.currency = MoneyUtil.DEFAULT_CURRENCY;
        this.status = TransactionStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public Transaction(String transactionId, String referenceNumber, String sourceWalletId,
                       String targetWalletId, BigDecimal amount, BigDecimal fee,
                       TransactionType type, String description) {
        this();
        this.transactionId = transactionId;
        this.referenceNumber = referenceNumber;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
        this.amount = amount != null ? MoneyUtil.validatePositiveAmount(amount) : MoneyUtil.ZERO;
        this.fee = fee != null ? MoneyUtil.validateNonNegativeAmount(fee) : MoneyUtil.ZERO;
        this.totalAmount = this.amount.add(this.fee).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        this.type = type;
        this.description = description;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getSourceWalletId() {
        return sourceWalletId;
    }

    public void setSourceWalletId(String sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
    }

    public String getTargetWalletId() {
        return targetWalletId;
    }

    public void setTargetWalletId(String targetWalletId) {
        this.targetWalletId = targetWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount != null ? MoneyUtil.validatePositiveAmount(amount) : MoneyUtil.ZERO;
        recalculateTotal();
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee != null ? MoneyUtil.validateNonNegativeAmount(fee) : MoneyUtil.ZERO;
        recalculateTotal();
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    private void recalculateTotal() {
        BigDecimal a = this.amount != null ? this.amount : MoneyUtil.ZERO;
        BigDecimal f = this.fee != null ? this.fee : MoneyUtil.ZERO;
        this.totalAmount = a.add(f).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = MoneyUtil.validateCurrency(currency);
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
        if (status == TransactionStatus.COMPLETED && this.completedAt == null) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    /**
     * Executes the transaction state transition to COMPLETED.
     */
    public void execute() {
        if (this.status == TransactionStatus.COMPLETED) {
            return;
        }
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Generates a readable receipt string for the transaction.
     */
    public String getReceipt() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("            OTOG E-WALLET RECEIPT        \n");
        sb.append("========================================\n");
        sb.append("Txn ID:       ").append(transactionId).append("\n");
        sb.append("Reference:    ").append(referenceNumber).append("\n");
        sb.append("Type:         ").append(type).append("\n");
        sb.append("Status:       ").append(status).append("\n");
        sb.append("Date:         ").append(createdAt != null ? createdAt.format(dtf) : "N/A").append("\n");
        if (sourceWalletId != null) {
            sb.append("Source:       ").append(sourceWalletId).append("\n");
        }
        if (targetWalletId != null) {
            sb.append("Destination:  ").append(targetWalletId).append("\n");
        }
        sb.append("Amount:       ").append(MoneyUtil.format(amount, currency)).append("\n");
        sb.append("Fee:          ").append(MoneyUtil.format(fee, currency)).append("\n");
        sb.append("----------------------------------------\n");
        sb.append("Total:        ").append(MoneyUtil.format(totalAmount, currency)).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("Note:         ").append(description).append("\n");
        }
        sb.append("========================================\n");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction that)) return false;
        return Objects.equals(transactionId, that.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "transactionId='" + transactionId + '\'' +
                ", referenceNumber='" + referenceNumber + '\'' +
                ", type=" + type +
                ", totalAmount=" + MoneyUtil.format(totalAmount, currency) +
                ", status=" + status +
                '}';
    }
}
