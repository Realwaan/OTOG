package com.otog.service;

import com.otog.exception.WalletException;
import com.otog.model.PaymentMethod;
import com.otog.model.Transaction;
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
}
