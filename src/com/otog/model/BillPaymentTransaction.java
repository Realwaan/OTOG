package com.otog.model;

import java.math.BigDecimal;

/**
 * Specialized transaction for utility and service bill payments.
 * Represents biller settlement with reference tracking and fund reservation.
 */
public class BillPaymentTransaction extends Transaction {
    private String billerName;
    private String billerReference;

    public BillPaymentTransaction() {
        super();
        setType(TransactionType.BILL_PAYMENT);
    }

    public BillPaymentTransaction(String transactionId, String referenceNumber, String sourceWalletId,
                                  BigDecimal amount, BigDecimal fee, String billerName,
                                  String billerReference, String description) {
        super(transactionId, referenceNumber, sourceWalletId, null, amount, fee,
                TransactionType.BILL_PAYMENT,
                description != null ? description : "Bill Payment to " + billerName);
        this.billerName = billerName;
        this.billerReference = billerReference;
    }

    public String getBillerName() {
        return billerName;
    }

    public void setBillerName(String billerName) {
        this.billerName = billerName;
    }

    public String getBillerReference() {
        return billerReference;
    }

    public void setBillerReference(String billerReference) {
        this.billerReference = billerReference;
    }

    @Override
    protected void appendSubtypeReceiptLines(ReceiptBuilder builder) {
        builder.optionalLine("Biller", billerName)
                .optionalLine("Account / Ref", billerReference);
    }

    @Override
    public BillPaymentTransaction copyAsDraft(String newReferenceNumber) {
        BillPaymentTransaction draft = new BillPaymentTransaction();
        copyBaseDraftFieldsTo(draft, newReferenceNumber);
        draft.setBillerName(this.billerName);
        draft.setBillerReference(this.billerReference);
        return draft;
    }
}
