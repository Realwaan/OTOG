package com.otog.service;

import com.otog.exception.EntityNotFoundException;
import com.otog.model.Notification;
import com.otog.model.PaymentMethod;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.Wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    Transaction transferWithPin(String referenceNumber, String sourceWalletId, String targetWalletId,
                                BigDecimal amount, BigDecimal fee, String description, String rawPin);

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

    default List<Transaction> getTransactionHistory(String walletId, TransactionFilter filter) {
        List<Transaction> all = getTransactionsByWalletId(walletId);
        TransactionFilter effective = filter != null ? filter : TransactionFilter.empty();
        List<Transaction> matched = new ArrayList<>();
        for (Transaction txn : all) {
            if (effective.matches(txn)) {
                matched.add(txn);
            }
        }
        matched.sort(TransactionFilter.NEWEST_FIRST);
        if (effective.getLimit() > 0 && matched.size() > effective.getLimit()) {
            return Collections.unmodifiableList(matched.subList(0, effective.getLimit()));
        }
        return Collections.unmodifiableList(matched);
    }

    default String getTransactionReceipt(String walletId, String transactionIdOrRef) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        if (transactionIdOrRef == null || transactionIdOrRef.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID or reference number must not be null or empty");
        }
        String cleanWallet = walletId.trim();
        String key = transactionIdOrRef.trim();
        for (Transaction txn : getTransactionsByWalletId(cleanWallet)) {
            if (key.equals(txn.getTransactionId()) || key.equals(txn.getReferenceNumber())) {
                return txn.getReceipt(cleanWallet, null);
            }
        }
        throw new EntityNotFoundException("Transaction", key);
    }

    default Transaction createRepeatTransactionDraft(String walletId, String transactionIdOrRef,
                                                     String newReferenceNumber) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        if (transactionIdOrRef == null || transactionIdOrRef.trim().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID or reference number must not be null or empty");
        }
        String cleanWallet = walletId.trim();
        String key = transactionIdOrRef.trim();
        for (Transaction txn : getTransactionsByWalletId(cleanWallet)) {
            if (key.equals(txn.getTransactionId()) || key.equals(txn.getReferenceNumber())) {
                return ReceiptBuilder.createRepeatDraft(txn, newReferenceNumber);
            }
        }
        throw new EntityNotFoundException("Transaction", key);
    }

    default List<Notification> getNotifications(String userId, boolean unreadOnly) {
        return Collections.emptyList();
    }

    default int getUnreadNotificationCount(String userId) {
        return 0;
    }

    default void markNotificationRead(String userId, String notificationId) {
    }

    default void markNotificationUnread(String userId, String notificationId) {
    }

    default int markAllNotificationsRead(String userId) {
        return 0;
    }

    default int retryPendingNotifications() {
        return 0;
    }

    default NotificationService getNotificationService() {
        return null;
    }

    default Transaction sendMoneyToRecipient(String referenceNumber, String sourceWalletId,
                                             String recipientIdentifier, BigDecimal amount,
                                             BigDecimal fee, String description) {
        return transfer(referenceNumber, sourceWalletId, recipientIdentifier, amount, fee, description);
    }

    default Transaction sendMoneyToRecipientWithPin(String referenceNumber, String sourceWalletId,
                                                    String recipientIdentifier, BigDecimal amount,
                                                    BigDecimal fee, String description, String rawPin) {
        return transferWithPin(referenceNumber, sourceWalletId, recipientIdentifier, amount, fee, description, rawPin);
    }

    Transaction cashOutWithPin(String referenceNumber, String sourceWalletId, String paymentMethodId,
                               BigDecimal amount, BigDecimal fee, String description, String rawPin);

    default Transaction payBill(String referenceNumber, String sourceWalletId, String billerName,
                                String billerReference, BigDecimal amount, BigDecimal fee, String description) {
        throw new UnsupportedOperationException("Bill payment not supported");
    }

    default Transaction payBillWithPin(String referenceNumber, String sourceWalletId, String billerName,
                                       String billerReference, BigDecimal amount, BigDecimal fee,
                                       String description, String rawPin) {
        throw new UnsupportedOperationException("Bill payment with PIN not supported");
    }

    default List<PaymentMethod> getPaymentMethodsByUserId(String userId) {
        return Collections.emptyList();
    }

    default Transaction statusLookup(String referenceNumber) {
        throw new UnsupportedOperationException("Status lookup not supported");
    }

    default void setPaymentProviderFactory(PaymentProviderFactory factory) {
    }

    default BankResponseStatus getCurrentProviderMode() {
        return BankResponseStatus.SUCCESS;
    }

    default AdminService getAdminService() {
        return null;
    }

    default SendMoneyService createSendMoneyService(AuthService authService) {
        return null;
    }

    default MerchantQrService createMerchantQrService(AuthService authService) {
        return null;
    }
}
