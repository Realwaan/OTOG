package com.otog.model;

import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

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
        this(transactionId, referenceNumber, walletId, amount, fee, product, units, TransactionType.INVESTMENT_BUY);
    }

    public InvestMoneyTransaction(String transactionId, String referenceNumber, String walletId,
                                  BigDecimal amount, BigDecimal fee, String product, BigDecimal units,
                                  TransactionType investmentType) {
        super(transactionId, referenceNumber,
                investmentType == TransactionType.INVESTMENT_REDEEM ? null : walletId,
                investmentType == TransactionType.INVESTMENT_REDEEM ? walletId : null,
                amount, fee,
                investmentType == TransactionType.INVESTMENT_REDEEM
                        ? TransactionType.INVESTMENT_REDEEM
                        : TransactionType.INVESTMENT_BUY,
                (investmentType == TransactionType.INVESTMENT_REDEEM ? "Redemption from " : "Investment in ") + product);
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
        return getReturns(OtogConfig.getInstance().getInvestmentReturnRate());
    }

    public BigDecimal getReturns(BigDecimal customRate) {
        if (getAmount() == null) {
            return MoneyUtil.ZERO;
        }
        BigDecimal effectiveRate = (customRate != null && customRate.compareTo(BigDecimal.ZERO) >= 0)
                ? customRate
                : OtogConfig.getInstance().getInvestmentReturnRate();
        return getAmount().multiply(effectiveRate).setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }

    @Override
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        builder.optionalLine("Product", product)
                .optionalLine("Units", units != null ? units.toPlainString() : null)
                .optionalLine("Est. Returns", MoneyUtil.format(getReturns(), getCurrency()));
    }

    @Override
    public InvestMoneyTransaction copyAsDraft(String newReferenceNumber) {
        InvestMoneyTransaction draft = new InvestMoneyTransaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        draft.setProduct(this.product);
        draft.setUnits(this.units);
        return draft;
    }
}
