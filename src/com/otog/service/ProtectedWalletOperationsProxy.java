package com.otog.service;

import com.otog.exception.WalletException;
import com.otog.model.Notification;
import com.otog.model.PaymentMethod;
import com.otog.model.Transaction;
import com.otog.model.TransactionFilter;
import com.otog.model.User;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Structural Protection Proxy guarding {@link WalletOperations} calls from the UI or external
 * callers by enforcing an active, non-suspended session and wallet ownership verification
 * before delegating to the underlying {@link WalletService} facade.
 */
public class ProtectedWalletOperationsProxy implements WalletOperations {

    private final WalletOperations delegate;

    public ProtectedWalletOperationsProxy(WalletOperations delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate WalletOperations cannot be null");
    }

    public static WalletOperations wrap(WalletOperations service) {
        if (service == null || service instanceof ProtectedWalletOperationsProxy) {
            return service;
        }
        return new ProtectedWalletOperationsProxy(service);
    }

    public WalletOperations getDelegate() {
        return delegate;
    }

    @Override
    public Wallet createWallet(String userId, BigDecimal initialBalance, String currency) {
        User caller = requireActiveUser();
        String cleanUserId = userId != null ? userId.trim() : null;
        if (!caller.isAdmin() && !caller.getUserId().equals(cleanUserId)) {
            throw new SecurityException("Access denied: Cannot provision a wallet for another user.");
        }
        return delegate.createWallet(userId, initialBalance, currency);
    }

    @Override
    public Wallet getWallet(String walletId) {
        requireActiveUser();
        return delegate.getWallet(walletId);
    }

    @Override
    public Wallet getWalletByUserId(String userId) {
        requireActiveUser();
        return delegate.getWalletByUserId(userId);
    }

    @Override
    public BigDecimal getBalance(String walletId) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.getBalance(walletId);
    }

    @Override
    public Transaction transfer(String referenceNumber, String sourceWalletId, String targetWalletId,
                                BigDecimal amount, BigDecimal fee, String description) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.transfer(referenceNumber, sourceWalletId, targetWalletId, amount, fee, description);
    }

    @Override
    public Transaction transferWithPin(String referenceNumber, String sourceWalletId, String targetWalletId,
                                       BigDecimal amount, BigDecimal fee, String description, String rawPin) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.transferWithPin(referenceNumber, sourceWalletId, targetWalletId, amount, fee, description, rawPin);
    }

    @Override
    public Transaction sendMoneyToRecipient(String referenceNumber, String sourceWalletId,
                                            String recipientIdentifier, BigDecimal amount,
                                            BigDecimal fee, String description) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.sendMoneyToRecipient(referenceNumber, sourceWalletId, recipientIdentifier, amount, fee, description);
    }

    @Override
    public Transaction sendMoneyToRecipientWithPin(String referenceNumber, String sourceWalletId,
                                                   String recipientIdentifier, BigDecimal amount,
                                                   BigDecimal fee, String description, String rawPin) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.sendMoneyToRecipientWithPin(referenceNumber, sourceWalletId, recipientIdentifier, amount, fee, description, rawPin);
    }

    @Override
    public Transaction cashIn(String referenceNumber, String targetWalletId, String paymentMethodId,
                              BigDecimal amount, String description) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, targetWalletId);
        return delegate.cashIn(referenceNumber, targetWalletId, paymentMethodId, amount, description);
    }

    @Override
    public Transaction cashOut(String referenceNumber, String sourceWalletId, String paymentMethodId,
                               BigDecimal amount, BigDecimal fee, String description) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.cashOut(referenceNumber, sourceWalletId, paymentMethodId, amount, fee, description);
    }

    @Override
    public Transaction cashOutWithPin(String referenceNumber, String sourceWalletId, String paymentMethodId,
                                      BigDecimal amount, BigDecimal fee, String description, String rawPin) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, sourceWalletId);
        return delegate.cashOutWithPin(referenceNumber, sourceWalletId, paymentMethodId, amount, fee, description, rawPin);
    }

    @Override
    public List<Transaction> getTransactionsByWalletId(String walletId) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.getTransactionsByWalletId(walletId);
    }

    @Override
    public List<Transaction> getRecentTransactions(String walletId, int limit) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.getRecentTransactions(walletId, limit);
    }

    @Override
    public List<Transaction> getTransactionHistory(String walletId, TransactionFilter filter) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.getTransactionHistory(walletId, filter);
    }

    @Override
    public String getTransactionReceipt(String walletId, String transactionIdOrRef) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.getTransactionReceipt(walletId, transactionIdOrRef);
    }

    @Override
    public Transaction createRepeatTransactionDraft(String walletId, String transactionIdOrRef,
                                                    String newReferenceNumber) {
        User caller = requireActiveUser();
        requireWalletOwnershipOrAdmin(caller, walletId);
        return delegate.createRepeatTransactionDraft(walletId, transactionIdOrRef, newReferenceNumber);
    }

    @Override
    public List<Notification> getNotifications(String userId, boolean unreadOnly) {
        User caller = requireActiveUser();
        requireUserOwnership(caller, userId, "Cannot read another user's notifications.");
        return delegate.getNotifications(userId, unreadOnly);
    }

    @Override
    public int getUnreadNotificationCount(String userId) {
        User caller = requireActiveUser();
        requireUserOwnership(caller, userId, "Cannot inspect another user's unread notifications.");
        return delegate.getUnreadNotificationCount(userId);
    }

    @Override
    public void markNotificationRead(String userId, String notificationId) {
        User caller = requireActiveUser();
        requireUserOwnership(caller, userId, "Cannot update another user's notification read status.");
        delegate.markNotificationRead(userId, notificationId);
    }

    @Override
    public void markNotificationUnread(String userId, String notificationId) {
        User caller = requireActiveUser();
        requireUserOwnership(caller, userId, "Cannot update another user's notification unread status.");
        delegate.markNotificationUnread(userId, notificationId);
    }

    @Override
    public int markAllNotificationsRead(String userId) {
        User caller = requireActiveUser();
        requireUserOwnership(caller, userId, "Cannot mark another user's notifications as read.");
        return delegate.markAllNotificationsRead(userId);
    }

    @Override
    public int retryPendingNotifications() {
        requireActiveUser();
        return delegate.retryPendingNotifications();
    }

    @Override
    public NotificationService getNotificationService() {
        requireActiveUser();
        return delegate.getNotificationService();
    }

    @Override
    public List<PaymentMethod> getPaymentMethodsByUserId(String userId) {
        User caller = requireActiveUser();
        String cleanUserId = userId != null ? userId.trim() : null;
        if (!caller.isAdmin() && !caller.getUserId().equals(cleanUserId)) {
            throw new SecurityException("Access denied: Cannot read another user's linked payment methods.");
        }
        return delegate.getPaymentMethodsByUserId(userId);
    }

    @Override
    public Transaction statusLookup(String referenceNumber) {
        requireActiveUser();
        return delegate.statusLookup(referenceNumber);
    }

    @Override
    public void setPaymentProviderFactory(PaymentProviderFactory factory) {
        requireActiveUser();
        delegate.setPaymentProviderFactory(factory);
    }

    @Override
    public BankResponseStatus getCurrentProviderMode() {
        return delegate.getCurrentProviderMode();
    }

    @Override
    public AdminService getAdminService() {
        return ProtectedAdminServiceProxy.wrap(delegate.getAdminService());
    }

    @Override
    public SendMoneyService createSendMoneyService(AuthService authService) {
        if (delegate instanceof WalletService ws) {
            return new SendMoneyService(this, ws.getUserRepository(), authService);
        }
        return delegate.createSendMoneyService(authService);
    }

    private static User requireActiveUser() {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();
        User currentUser = session.getCurrentUser();
        if (currentUser == null) {
            throw new SecurityException("Access denied: Active authenticated session required.");
        }
        if (currentUser.getStatus() == UserStatus.SUSPENDED) {
            throw new WalletException("Suspended accounts cannot access wallet operations.");
        }
        if (currentUser.getStatus() == UserStatus.LOCKED) {
            throw new WalletException("Locked accounts cannot access wallet operations.");
        }
        if (currentUser.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: User account is " + currentUser.getStatus() + ".");
        }
        return currentUser;
    }

    private void requireWalletOwnershipOrAdmin(User caller, String walletId) {
        if (walletId == null || walletId.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet ID must not be null or empty");
        }
        if (caller.isAdmin()) {
            return;
        }
        Wallet wallet = delegate.getWallet(walletId.trim());
        if (!caller.getUserId().equals(wallet.getUserId())) {
            throw new SecurityException("Access denied: Wallet " + walletId + " does not belong to active user.");
        }
    }

    private static void requireUserOwnership(User caller, String targetUserId, String denialMessage) {
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID must not be null or empty");
        }
        if (!caller.getUserId().equals(targetUserId.trim())) {
            throw new SecurityException("Access denied: " + denialMessage);
        }
    }
}
