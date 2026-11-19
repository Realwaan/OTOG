package com.otog.model;

import java.math.BigDecimal;

/**
 * Specialized transaction for Cash In and Cash Out operations via banking / OTC channels.
 * Matches CashInOut in class diagram.
 */
public class CashInOutTransaction extends Transaction {
    private String cashOperationType; // e.g. "CASH_IN" or "CASH_OUT"
    private String channel;           // e.g. "BDO", "7-Eleven OTC"

    public CashInOutTransaction() {
        super();
    }

    public CashInOutTransaction(String transactionId, String referenceNumber, String walletId,
                                BigDecimal amount, BigDecimal fee, String cashOperationType, String channel) {
        super(transactionId, referenceNumber,
                "CASH_OUT".equalsIgnoreCase(cashOperationType) ? walletId : null,
                "CASH_IN".equalsIgnoreCase(cashOperationType) ? walletId : null,
                amount, fee,
                "CASH_IN".equalsIgnoreCase(cashOperationType) ? TransactionType.CASH_IN : TransactionType.CASH_OUT,
                cashOperationType + " via " + channel);
        this.cashOperationType = cashOperationType;
        this.channel = channel;
    }

    public CashInOutTransaction(String transactionId, String referenceNumber, String walletId,
                                BigDecimal amount, BigDecimal fee, String cashOperationType, String channel,
                                String providerReference) {
        this(transactionId, referenceNumber, walletId, amount, fee, cashOperationType, channel);
        setProviderReference(providerReference);
    }

    public String getCashOperationType() {
        return cashOperationType;
    }

    public void setCashOperationType(String cashOperationType) {
        this.cashOperationType = cashOperationType;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public void cashIn() {
        this.cashOperationType = "CASH_IN";
        setType(TransactionType.CASH_IN);
    }

    public void cashOut() {
        this.cashOperationType = "CASH_OUT";
        setType(TransactionType.CASH_OUT);
    }

    @Override
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        builder.line("Operation", cashOperationType != null ? cashOperationType : getType())
                .optionalLine("Channel", channel);
    }

    @Override
    public CashInOutTransaction copyAsDraft(String newReferenceNumber) {
        CashInOutTransaction draft = new CashInOutTransaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        draft.setCashOperationType(this.cashOperationType);
        draft.setChannel(this.channel);
        return draft;
    }
}
