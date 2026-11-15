package com.otog.service;

import com.otog.model.Transaction;
import com.otog.model.Wallet;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Shared service contract for core wallet operations across UI and domain modules.
 */
public interface WalletOperations {
    Wallet createWallet(String userId, BigDecimal initialBalance, String currency);

    Wallet getWallet(String walletId);

    Wallet getWalletByUserId(String userId);

    BigDecimal getBalance(String walletId);

    Transaction transfer(String referenceNumber, String sourceWalletId, String targetWalletId,
                         BigDecimal amount, BigDecimal fee, String description);

    Transaction cashIn(String referenceNumber, String targetWalletId, String paymentMethodId,
                       BigDecimal amount, String description);

    Transaction cashOut(String referenceNumber, String sourceWalletId, String paymentMethodId,
                        BigDecimal amount, BigDecimal fee, String description);

    default List<Transaction> getTransactionsByWalletId(String walletId) {
        return Collections.emptyList();
    }

    default List<Transaction> getRecentTransactions(String walletId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Transaction limit must be greater than zero");
        }
        List<Transaction> all = getTransactionsByWalletId(walletId);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(0, limit);
    }

    default Transaction sendMoneyToRecipient(String referenceNumber, String sourceWalletId,
                                             String recipientIdentifier, BigDecimal amount,
                                             BigDecimal fee, String description) {
        return transfer(referenceNumber, sourceWalletId, recipientIdentifier, amount, fee, description);
    }
}
