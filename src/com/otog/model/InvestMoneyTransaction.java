package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Specialized transaction for investments.
 * Matches InvestMoney in class diagram.
 */
public class InvestMoneyTransaction extends Transaction {
    private String product;
    private BigDecimal units;

    public InvestMoneyTransaction() {
        super();
        setType(TransactionType.INVESTMENT_BUY);
    }

    public InvestMoneyTransaction(String transactionId, String referenceNumber, String walletId,
                                  BigDecimal amount, BigDecimal fee, String product, BigDecimal units) {
        super(transactionId, referenceNumber, walletId, null, amount, fee,
                TransactionType.INVESTMENT_BUY, "Investment in " + product);
        this.product = product;
        this.units = units != null ? units : BigDecimal.ONE;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public BigDecimal getUnits() {
        return units;
    }

    public void setUnits(BigDecimal units) {
        this.units = units;
    }

    public void invest() {
        setType(TransactionType.INVESTMENT_BUY);
        execute();
    }

    public void withdraw() {
        setType(TransactionType.INVESTMENT_REDEEM);
        execute();
    }

    public BigDecimal getReturns() {
        if (getAmount() == null) return MoneyUtil.ZERO;
        return getAmount().multiply(new BigDecimal("0.05")).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }
}
