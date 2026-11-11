package com.otog.model;

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
        return recipientNumber != null && recipientNumber.trim().length() >= 10;
    }

    public void send() {
        execute();
    }
}
