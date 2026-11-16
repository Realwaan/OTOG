package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Creational Factory for producing specialized {@link Transaction} instances.
 * Delegates construction and validation to {@link TransactionBuilder}.
 */
public class TransactionFactory {

    private TransactionFactory() {
        // Factory utility class
    }

    /**
     * Creates a {@link SendMoneyTransaction} with all required and optional fields.
     *
     * @param referenceNumber unique idempotency reference number
     * @param sourceWalletId  source (sender) wallet ID
     * @param targetWalletId  target (recipient) wallet ID
     * @param amount          monetary transfer amount
     * @param fee             service or transaction fee
     * @param recipientNumber recipient mobile number or identifier
     * @param note            optional transfer note / description
     * @return constructed SendMoneyTransaction in PENDING status
     */
    public static SendMoneyTransaction createSendMoney(String referenceNumber,
                                                       String sourceWalletId,
                                                       String targetWalletId,
                                                       BigDecimal amount,
                                                       BigDecimal fee,
                                                       String recipientNumber,
                                                       String note) {
        return new TransactionBuilder()
                .referenceNumber(referenceNumber)
                .from(sourceWalletId)
                .to(targetWalletId)
                .amount(amount)
                .fee(fee)
                .recipientNumber(recipientNumber)
                .note(note)
                .build();
    }

    /**
     * Creates a {@link SendMoneyTransaction} with zero fee.
     */
    public static SendMoneyTransaction createSendMoney(String referenceNumber,
                                                       String sourceWalletId,
                                                       String targetWalletId,
                                                       BigDecimal amount,
                                                       String recipientNumber,
                                                       String note) {
        return createSendMoney(referenceNumber, sourceWalletId, targetWalletId,
                amount, MoneyUtil.ZERO, recipientNumber, note);
    }
}

