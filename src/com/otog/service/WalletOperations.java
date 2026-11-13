package com.otog.service;

import com.otog.model.Transaction;
import com.otog.model.Wallet;

import java.math.BigDecimal;

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
}
