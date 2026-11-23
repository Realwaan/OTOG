package com.otog.model;

import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;

/**
 * Represents a financial transaction within the OTOG E-Wallet platform.
 * Supports idempotency via unique referenceNumber, {@link TransactionPrototype} draft cloning,
 * {@link ReceiptComponent} composite receipt generation, and {@link BigDecimal} financial precision.
 */
public class Transaction implements Comparable<Transaction>, TransactionPrototype {
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
    private String providerReference;
    private String providerStatus;

    public Transaction() {
        this.amount = MoneyUtil.ZERO;
        this.fee = MoneyUtil.ZERO;
        this.totalAmount = MoneyUtil.ZERO;
        this.currency = OtogConfig.getInstance().getDefaultCurrency();
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

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
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
     * Generates a Composite {@link ReceiptComponent} tree for this transaction via {@link ReceiptBuilder}
     * with masked identifiers, counterparty summary, and formatted timestamps.
     */
    public ReceiptComponent buildReceiptComponent() {
        return buildReceiptComponent(null, null);
    }

    /**
     * Generates a Composite {@link ReceiptComponent} tree tailored for a viewing wallet and resolved counterparty.
     */
    public ReceiptComponent buildReceiptComponent(String viewingWalletId, String counterpartyDisplay) {
        return ReceiptBuilder.forTransaction(this, viewingWalletId, counterpartyDisplay).buildComponent();
    }

    /**
     * Hook for specialized {@link Transaction} subclasses to contribute subtype-specific receipt fields.
     */
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        // Base Transaction has no subtype-specific fields
    }

    /**
     * Generates a readable masked receipt string for the transaction using its Composite receipt tree.
     */
    public String getReceipt() {
        return buildReceiptComponent().renderToString(ReceiptBuilder.DEFAULT_LABEL_WIDTH);
    }

    /**
     * Generates a readable masked receipt string with resolved counterparty context for a specific wallet.
     */
    public String getReceipt(String viewingWalletId, String counterpartyDisplay) {
        return buildReceiptComponent(viewingWalletId, counterpartyDisplay)
                .renderToString(ReceiptBuilder.DEFAULT_LABEL_WIDTH);
    }

    @Override
    public Transaction copyAsDraft(String newReferenceNumber) {
        Transaction draft = new Transaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        return draft;
    }

    protected final void copyBaseDraftFieldsTo(Transaction target, String newReferenceNumber) {
        String effectiveRef = (newReferenceNumber != null && !newReferenceNumber.trim().isEmpty())
                ? newReferenceNumber.trim()
                : IdGenerator.nextReferenceNumber();
        target.setTransactionId(IdGenerator.nextTransactionId());
        target.setReferenceNumber(effectiveRef);
        target.setSourceWalletId(this.sourceWalletId);
        target.setTargetWalletId(this.targetWalletId);
        if (this.amount != null && this.amount.compareTo(BigDecimal.ZERO) > 0) {
            target.setAmount(this.amount);
        }
        target.setFee(this.fee);
        target.setCurrency(this.currency);
        target.setType(this.type);
        target.setStatus(TransactionStatus.PENDING);
        target.setDescription(this.description);
        target.setCreatedAt(LocalDateTime.now());
        target.setCompletedAt(null);
        target.setProviderReference(null);
        target.setProviderStatus(null);
    }

    private static final Comparator<Transaction> NEWEST_FIRST = Comparator
            .comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Transaction::getTransactionId, Comparator.nullsLast(Comparator.reverseOrder()))
            // Final tie-breaker keeps compareTo consistent with equals() when timestamps and IDs match.
            .thenComparing(Transaction::getReferenceNumber, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public int compareTo(Transaction other) {
        if (other == null) {
            return -1;
        }
        return NEWEST_FIRST.compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction that)) return false;
        return Objects.equals(transactionId, that.transactionId)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(referenceNumber, that.referenceNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, createdAt, referenceNumber);
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
