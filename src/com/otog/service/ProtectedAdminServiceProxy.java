package com.otog.service;

import com.otog.model.AdminAction;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Structural Protection Proxy enforcing active session authentication and role-based
 * access control ({@link UserRole#ADMIN} / self-access checks) before delegating calls
 * to the underlying {@link AdminService}.
 */
public class ProtectedAdminServiceProxy implements AdminService {

    private final AdminService delegate;

    public ProtectedAdminServiceProxy(AdminService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate AdminService cannot be null");
    }

    public static AdminService wrap(AdminService adminService) {
        if (adminService == null || adminService instanceof ProtectedAdminServiceProxy) {
            return adminService;
        }
        return new ProtectedAdminServiceProxy(adminService);
    }

    public AdminService getDelegate() {
        return delegate;
    }

    @Override
    public void suspendUser(String adminUserId, String targetUserId, String reason) {
        requireAdminCaller(adminUserId, "suspend user accounts");
        delegate.suspendUser(adminUserId, targetUserId, reason);
    }

    @Override
    public void reactivateUser(String adminUserId, String targetUserId, String reason) {
        requireAdminCaller(adminUserId, "reactivate user accounts");
        delegate.reactivateUser(adminUserId, targetUserId, reason);
    }

    @Override
    public List<User> getAllUsers(String requesterUserId) {
        requireAdminCaller(requesterUserId, "view all user accounts");
        return delegate.getAllUsers(requesterUserId);
    }

    @Override
    public List<Wallet> getAllWallets(String requesterUserId) {
        requireAdminCaller(requesterUserId, "view all wallet accounts");
        return delegate.getAllWallets(requesterUserId);
    }

    @Override
    public List<Transaction> getAllTransactions(String requesterUserId) {
        requireAdminCaller(requesterUserId, "view all system transactions");
        return delegate.getAllTransactions(requesterUserId);
    }

    @Override
    public List<Transaction> getUserTransactions(String requesterUserId, String targetUserId) {
        User caller = requireAuthenticatedCaller(requesterUserId);
        String cleanTarget = targetUserId != null ? targetUserId.trim() : null;
        if (!caller.isAdmin() && !caller.getUserId().equals(cleanTarget)) {
            throw new SecurityException("Access denied: Only administrators may inspect another user's transaction history.");
        }
        return delegate.getUserTransactions(requesterUserId, targetUserId);
    }

    @Override
    public Wallet getUserWallet(String requesterUserId, String targetUserId) {
        User caller = requireAuthenticatedCaller(requesterUserId);
        String cleanTarget = targetUserId != null ? targetUserId.trim() : null;
        if (!caller.isAdmin() && !caller.getUserId().equals(cleanTarget)) {
            throw new SecurityException("Access denied: Only administrators may inspect another user's wallet.");
        }
        return delegate.getUserWallet(requesterUserId, targetUserId);
    }

    @Override
    public void changeUserRole(String adminUserId, String targetUserId, UserRole newRole, String reason) {
        requireAdminCaller(adminUserId, "change user roles");
        delegate.changeUserRole(adminUserId, targetUserId, newRole, reason);
    }

    @Override
    public List<AdminAction> getAdminActions(String requesterUserId) {
        requireAdminCaller(requesterUserId, "view administrative audit logs");
        return delegate.getAdminActions(requesterUserId);
    }

    @Override
    public void directSetBalance(String requesterUserId, String walletId, BigDecimal newBalance) {
        requireAuthenticatedCaller(requesterUserId);
        delegate.directSetBalance(requesterUserId, walletId, newBalance);
    }

    private User requireAuthenticatedCaller(String suppliedUserId) {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();
        if (suppliedUserId != null && !suppliedUserId.trim().isEmpty()) {
            session.requireActiveSessionFor(suppliedUserId.trim());
        }
        User currentUser = session.getCurrentUser();
        if (currentUser == null) {
            throw new SecurityException("Access denied: Active authenticated session required.");
        }
        if (currentUser.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Caller account status is " + currentUser.getStatus() + ".");
        }
        return currentUser;
    }

    private User requireAdminCaller(String suppliedUserId, String operationDescription) {
        User caller = requireAuthenticatedCaller(suppliedUserId);
        if (!caller.isAdmin()) {
            throw new SecurityException("Access denied: Administrator privileges required to "
                    + operationDescription + ". Current role: " + caller.getRole());
        }
        return caller;
    }
}
