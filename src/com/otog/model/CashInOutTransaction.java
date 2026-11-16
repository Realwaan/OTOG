package com.otog.model;

import java.math.BigDecimal;

/**
 * Specialized transaction for Cash In and Cash Out operations via banking / OTC channels.
 * Matches CashInOut in class diagram.
 */
public class CashInOutTransaction extends Transaction {
    private String cashOperationType; // "CASH_IN" or "CASH_OUT"
    private String channel;           // e.g. "BDO", "7-Eleven", "Over-The-Counter"

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
    public String getReceipt() {
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("            OTOG E-WALLET RECEIPT        \n");
        sb.append("========================================\n");
        sb.append("Txn ID:       ").append(getTransactionId()).append("\n");
        sb.append("Reference:    ").append(getReferenceNumber()).append("\n");
        if (getProviderReference() != null && !getProviderReference().isEmpty()) {
            sb.append("Provider Ref: ").append(getProviderReference()).append("\n");
        }
        sb.append("Operation:    ").append(cashOperationType != null ? cashOperationType : getType()).append("\n");
        if (channel != null && !channel.isEmpty()) {
            sb.append("Channel:      ").append(channel).append("\n");
        }
        sb.append("Type:         ").append(getType()).append("\n");
        sb.append("Status:       ").append(getStatus()).append("\n");
        if (getProviderStatus() != null && !getProviderStatus().isEmpty()) {
            sb.append("Prov Status:  ").append(getProviderStatus()).append("\n");
        }
        sb.append("Date:         ").append(getCreatedAt() != null ? getCreatedAt().format(dtf) : "N/A").append("\n");
        if (getSourceWalletId() != null) {
            sb.append("Source:       ").append(getSourceWalletId()).append("\n");
        }
        if (getTargetWalletId() != null) {
            sb.append("Destination:  ").append(getTargetWalletId()).append("\n");
        }
        sb.append("Amount:       ").append(com.otog.util.MoneyUtil.format(getAmount(), getCurrency())).append("\n");
        sb.append("Fee:          ").append(com.otog.util.MoneyUtil.format(getFee(), getCurrency())).append("\n");
        sb.append("----------------------------------------\n");
        sb.append("Total:        ").append(com.otog.util.MoneyUtil.format(getTotalAmount(), getCurrency())).append("\n");
        if (getDescription() != null && !getDescription().isEmpty()) {
            sb.append("Note:         ").append(getDescription()).append("\n");
        }
        sb.append("========================================\n");
        return sb.toString();
    }
}
