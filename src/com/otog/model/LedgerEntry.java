package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents an immutable accounting ledger entry associated with a transaction and a wallet.
 * Every wallet balance change produces a corresponding ledger record preserving financial auditability.
 */
public class LedgerEntry {
    private String ledgerEntryId;
    private String transactionId;
    private String walletId;
    private LedgerEntryType entryType;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String currency;
    private String description;
    private LocalDateTime createdAt;

    public LedgerEntry() {
        this.amount = MoneyUtil.ZERO;
        this.balanceBefore = MoneyUtil.ZERO;
        this.balanceAfter = MoneyUtil.ZERO;
        this.currency = MoneyUtil.DEFAULT_CURRENCY;
        this.createdAt = LocalDateTime.now();
    }

    public LedgerEntry(String ledgerEntryId, String transactionId, String walletId,
                       LedgerEntryType entryType, BigDecimal amount,
                       BigDecimal balanceBefore, BigDecimal balanceAfter,
                       String currency, String description) {
        this.ledgerEntryId = ledgerEntryId;
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount != null ? MoneyUtil.validatePositiveAmount(amount) : MoneyUtil.ZERO;
        this.balanceBefore = balanceBefore != null ? balanceBefore.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING) : MoneyUtil.ZERO;
        this.balanceAfter = balanceAfter != null ? balanceAfter.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING) : MoneyUtil.ZERO;
        this.currency = MoneyUtil.validateCurrency(currency);
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public String getLedgerEntryId() {
        return ledgerEntryId;
    }

    public void setLedgerEntryId(String ledgerEntryId) {
        this.ledgerEntryId = ledgerEntryId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(LedgerEntryType entryType) {
        this.entryType = entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount != null ? MoneyUtil.validatePositiveAmount(amount) : MoneyUtil.ZERO;
    }

    public BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    public void setBalanceBefore(BigDecimal balanceBefore) {
        this.balanceBefore = balanceBefore != null ? balanceBefore.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING) : MoneyUtil.ZERO;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter != null ? balanceAfter.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING) : MoneyUtil.ZERO;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = MoneyUtil.validateCurrency(currency);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntry that)) return false;
        return Objects.equals(ledgerEntryId, that.ledgerEntryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ledgerEntryId);
    }

    @Override
    public String toString() {
        return "LedgerEntry{" +
                "ledgerEntryId='" + ledgerEntryId + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", walletId='" + walletId + '\'' +
                ", entryType=" + entryType +
                ", amount=" + MoneyUtil.format(amount, currency) +
                ", balanceBefore=" + balanceBefore +
                ", balanceAfter=" + balanceAfter +
                '}';
    }
}
