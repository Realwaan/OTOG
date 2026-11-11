package com.otog.model;

import com.otog.exception.InsufficientFundsException;
import com.otog.exception.WalletException;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a digital wallet owned by a user.
 * Encapsulates balance modifications and fund availability checks using BigDecimal.
 */
public class Wallet {
    private String walletId;
    private String userId;
    private BigDecimal balance;
    private String currency;
    private WalletStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Wallet() {
        this.balance = MoneyUtil.ZERO;
        this.currency = MoneyUtil.DEFAULT_CURRENCY;
        this.status = WalletStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Wallet(String walletId, String userId) {
        this();
        this.walletId = walletId;
        this.userId = userId;
    }

    public Wallet(String walletId, String userId, BigDecimal initialBalance, String currency) {
        this(walletId, userId);
        this.balance = initialBalance != null ? MoneyUtil.validateNonNegativeAmount(initialBalance) : MoneyUtil.ZERO;
        this.currency = MoneyUtil.validateCurrency(currency);
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance != null ? MoneyUtil.validateNonNegativeAmount(balance) : MoneyUtil.ZERO;
        this.updatedAt = LocalDateTime.now();
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = MoneyUtil.validateCurrency(currency);
    }

    public WalletStatus getStatus() {
        return status;
    }

    public void setStatus(WalletStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Deducts an amount from the wallet.
     * Validates positive amount, active status, and sufficient funds.
     *
     * @param amount amount to deduct
     * @throws InsufficientFundsException if balance is less than amount
     * @throws WalletException if wallet is not active
     */
    public synchronized void debit(BigDecimal amount) {
        if (status != WalletStatus.ACTIVE) {
            throw new WalletException("Wallet is not active (status=" + status + ")");
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        if (this.balance.compareTo(validAmount) < 0) {
            throw new InsufficientFundsException(walletId, this.balance, validAmount);
        }
        this.balance = this.balance.subtract(validAmount).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Credits an amount into the wallet.
     * Validates positive amount and active status.
     *
     * @param amount amount to add
     * @throws WalletException if wallet is not active
     */
    public synchronized void credit(BigDecimal amount) {
        if (status != WalletStatus.ACTIVE) {
            throw new WalletException("Wallet is not active (status=" + status + ")");
        }
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        this.balance = this.balance.add(validAmount).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Wallet wallet)) return false;
        return Objects.equals(walletId, wallet.walletId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(walletId);
    }

    @Override
    public String toString() {
        return "Wallet{" +
                "walletId='" + walletId + '\'' +
                ", userId='" + userId + '\'' +
                ", balance=" + MoneyUtil.format(balance, currency) +
                ", status=" + status +
                '}';
    }
}
