package com.otog.model;

import com.otog.util.OtogConfig;

import java.math.BigDecimal;

/**
 * Specialized transaction for peer-to-peer money transfers between users.
 * Matches SendMoney in class diagram.
 */
public class SendMoneyTransaction extends Transaction {
    private String recipientNumber;
    private String note;

    public SendMoneyTransaction() {
        super();
        setType(TransactionType.SEND_MONEY);
    }

    public SendMoneyTransaction(String transactionId, String referenceNumber, String sourceWalletId,
                                String targetWalletId, BigDecimal amount, BigDecimal fee,
                                String recipientNumber, String note) {
        super(transactionId, referenceNumber, sourceWalletId, targetWalletId, amount, fee,
                TransactionType.SEND_MONEY, note != null ? note : "Transfer to " + recipientNumber);
        this.recipientNumber = recipientNumber;
        this.note = note;
    }

    public String getRecipientNumber() {
        return recipientNumber;
    }

    public void setRecipientNumber(String recipientNumber) {
        this.recipientNumber = recipientNumber;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean validateRecipient() {
        int minLength = OtogConfig.getInstance().getMinRecipientLength();
        return recipientNumber != null && recipientNumber.trim().length() >= minLength;
    }

    public void send() {
        execute();
    }

    @Override
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        builder.optionalLine("Recipient", recipientNumber);
    }

    @Override
    public SendMoneyTransaction copyAsDraft(String newReferenceNumber) {
        SendMoneyTransaction draft = new SendMoneyTransaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        draft.setRecipientNumber(this.recipientNumber);
        draft.setNote(this.note);
        return draft;
    }
}
