package com.otog.service;

import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.OtogException;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.util.IdGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementation of AdminService enforcing role-based access control,
 * account suspension/reactivation, traceable audit logs without secrets,
 * and strict prohibition of direct balance modifications.
 */
public class AdminServiceImpl implements AdminService {
    private final DatabaseManager databaseManager;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AdminActionRepository adminActionRepository;
    private final CredentialRepository credentialRepository;

    public AdminServiceImpl(DatabaseManager databaseManager) {
        this(databaseManager,
                new UserRepository(databaseManager),
                new WalletRepository(databaseManager),
                new TransactionRepository(databaseManager),
                new AdminActionRepository(databaseManager),
                new CredentialRepository(databaseManager));
    }

    public AdminServiceImpl(DatabaseManager databaseManager,
                            UserRepository userRepository,
                            WalletRepository walletRepository,
                            TransactionRepository transactionRepository,
                            AdminActionRepository adminActionRepository,
                            CredentialRepository credentialRepository) {
        this.databaseManager = databaseManager;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.adminActionRepository = adminActionRepository;
        this.credentialRepository = credentialRepository;
    }

    private User validateAdminCaller(String adminUserId, String operationDescription) {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();

        if (adminUserId != null && !adminUserId.trim().isEmpty()) {
            session.requireActiveSessionFor(adminUserId.trim());
        }

        User caller = session.getCurrentUser();
        if (caller == null) {
            throw new SecurityException("Access denied: Active administrator identity required to " + operationDescription + ".");
        }

        if (caller.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Administrator account is " + caller.getStatus() + ".");
        }
        if (caller.getRole() != UserRole.ADMIN) {
            throw new SecurityException("Access denied: Administrator role required to " + operationDescription + ". Current role: " + caller.getRole());
        }
        return caller;
    }

    @Override
    public void suspendUser(String adminUserId, String targetUserId, String reason) {
        User admin = validateAdminCaller(adminUserId, "suspend accounts");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String targetId = targetUserId.trim();
        if (admin.getUserId().equals(targetId)) {
            throw new OtogException("Administrators cannot suspend their own account.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A recorded reason is mandatory for account suspension.");
        }

        // Atomic multi-table write boundary
        databaseManager.executeInTransaction(conn -> {
            User targetUser = userRepository.findById(targetId, conn)
                    .orElseThrow(() -> new EntityNotFoundException("User", targetId));

            targetUser.setStatus(UserStatus.SUSPENDED);
            targetUser.setUpdatedAt(LocalDateTime.now());
            userRepository.update(targetUser, conn);

            // Freeze associated wallet
            walletRepository.findByUserId(targetId, conn).ifPresent(wallet -> {
                wallet.setStatus(WalletStatus.FROZEN);
                wallet.setUpdatedAt(LocalDateTime.now());
                try {
                    walletRepository.update(wallet, conn);
                } catch (Exception e) {
                    throw new OtogException("Failed to freeze wallet for suspended user: " + e.getMessage(), e);
                }
            });

            // Record traceable administrative action without secrets
            String actionId = IdGenerator.nextAdminActionId();
            String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
            String safeDetails = AdminAction.sanitizeWithoutSecrets("Suspended user " + targetId + " (" + targetUser.getName() + "). Reason: " + safeReason);
            AdminAction action = new AdminAction(actionId, admin.getUserId(), "SUSPEND_USER", targetId, safeReason, safeDetails);
            try {
                adminActionRepository.save(action, conn);
            } catch (Exception e) {
                throw new OtogException("Failed to record admin suspension action: " + e.getMessage(), e);
            }
            return null;
        });

        // Terminate any active session for the suspended user
        SessionManager session = SessionManager.getInstance();
        if (session.isSessionActive(targetId)) {
            session.logout();
        }
    }

    @Override
    public void reactivateUser(String adminUserId, String targetUserId, String reason) {
        User admin = validateAdminCaller(adminUserId, "reactivate accounts");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String targetId = targetUserId.trim();
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A recorded reason is mandatory for account reactivation.");
        }

        // Atomic multi-table write boundary
        databaseManager.executeInTransaction(conn -> {
            User targetUser = userRepository.findById(targetId, conn)
                    .orElseThrow(() -> new EntityNotFoundException("User", targetId));

            targetUser.setStatus(UserStatus.ACTIVE);
            targetUser.setUpdatedAt(LocalDateTime.now());
            userRepository.update(targetUser, conn);

            // Unfreeze associated wallet
            walletRepository.findByUserId(targetId, conn).ifPresent(wallet -> {
                wallet.setStatus(WalletStatus.ACTIVE);
                wallet.setUpdatedAt(LocalDateTime.now());
                try {
                    walletRepository.update(wallet, conn);
                } catch (Exception e) {
                    throw new OtogException("Failed to unfreeze wallet for reactivated user: " + e.getMessage(), e);
                }
            });

            // Reset failed login attempts AND failed PIN attempts upon admin reactivation
            try {
                credentialRepository.resetFailedAttempts(targetId, conn);
                credentialRepository.resetPinFailedAttempts(targetId, conn);
            } catch (Exception e) {
                throw new OtogException("Failed to reset credential attempts upon reactivation: " + e.getMessage(), e);
            }

            // Record traceable administrative action without secrets
            String actionId = IdGenerator.nextAdminActionId();
            String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
            String safeDetails = AdminAction.sanitizeWithoutSecrets("Reactivated user " + targetId + " (" + targetUser.getName() + "). Reason: " + safeReason);
            AdminAction action = new AdminAction(actionId, admin.getUserId(), "REACTIVATE_USER", targetId, safeReason, safeDetails);
            try {
                adminActionRepository.save(action, conn);
            } catch (Exception e) {
                throw new OtogException("Failed to record admin reactivation action: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public List<User> getAllUsers(String requesterUserId) {
        User admin = validateAdminCaller(requesterUserId, "view all user accounts");
        List<User> users = userRepository.findAll();

        String actionId = IdGenerator.nextAdminActionId();
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "VIEW_ACCOUNTS", null,
                "Administrative review", "Retrieved all registered user accounts (count=" + users.size() + ")");
        adminActionRepository.save(action);

        return users;
    }

    @Override
    public List<Wallet> getAllWallets(String requesterUserId) {
        User admin = validateAdminCaller(requesterUserId, "view all wallet accounts");
        List<Wallet> wallets = walletRepository.findAll();

        String actionId = IdGenerator.nextAdminActionId();
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "VIEW_WALLETS", null,
                "Administrative review", "Retrieved all wallet accounts (count=" + wallets.size() + ")");
        adminActionRepository.save(action);

        return wallets;
    }

    @Override
    public List<Transaction> getAllTransactions(String requesterUserId) {
        User admin = validateAdminCaller(requesterUserId, "view all system transactions");
        List<Transaction> transactions = transactionRepository.findAll();

        String actionId = IdGenerator.nextAdminActionId();
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "VIEW_TRANSACTIONS", null,
                "Administrative review", "Retrieved all system transactions (count=" + transactions.size() + ")");
        adminActionRepository.save(action);

        return transactions;
    }

    @Override
    public List<Transaction> getUserTransactions(String requesterUserId, String targetUserId) {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();

        if (requesterUserId != null && !requesterUserId.trim().isEmpty()) {
            session.requireActiveSessionFor(requesterUserId.trim());
        }

        User requester = session.getCurrentUser();
        String reqId = requester.getUserId();

        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String tgtId = targetUserId.trim();

        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Requester account is " + requester.getStatus() + ".");
        }

        // Ordinary users (USER / MERCHANT) can ONLY view their own history; only ADMIN can view another user's history
        if (requester.getRole() != UserRole.ADMIN && !reqId.equals(tgtId)) {
            throw new SecurityException("Access denied: Ordinary users cannot inspect another user's transaction history.");
        }

        Wallet targetWallet = walletRepository.findByUserId(tgtId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet for user", tgtId));

        List<Transaction> txns = transactionRepository.findByWalletId(targetWallet.getWalletId());

        if (requester.getRole() == UserRole.ADMIN && !reqId.equals(tgtId)) {
            String actionId = IdGenerator.nextAdminActionId();
            AdminAction action = new AdminAction(actionId, reqId, "INSPECT_USER_HISTORY", tgtId,
                    "Administrative inspection", "Inspected transaction history of user " + tgtId);
            adminActionRepository.save(action);
        }

        return txns;
    }

    @Override
    public Wallet getUserWallet(String requesterUserId, String targetUserId) {
        SessionManager session = SessionManager.getInstance();
        session.requireAuthentication();

        if (requesterUserId != null && !requesterUserId.trim().isEmpty()) {
            session.requireActiveSessionFor(requesterUserId.trim());
        }

        User requester = session.getCurrentUser();
        String reqId = requester.getUserId();

        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String tgtId = targetUserId.trim();

        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Requester account is " + requester.getStatus() + ".");
        }

        if (requester.getRole() != UserRole.ADMIN && !reqId.equals(tgtId)) {
            throw new SecurityException("Access denied: Ordinary users cannot inspect another user's wallet.");
        }

        Wallet wallet = walletRepository.findByUserId(tgtId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet for user", tgtId));

        if (requester.getRole() == UserRole.ADMIN && !reqId.equals(tgtId)) {
            String actionId = IdGenerator.nextAdminActionId();
            AdminAction action = new AdminAction(actionId, reqId, "INSPECT_USER_WALLET", tgtId,
                    "Administrative inspection", "Inspected wallet of user " + tgtId);
            adminActionRepository.save(action);
        }

        return wallet;
    }

    @Override
    public void changeUserRole(String adminUserId, String targetUserId, UserRole newRole, String reason) {
        User admin = validateAdminCaller(adminUserId, "change user roles");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        if (newRole == null) {
            throw new IllegalArgumentException("New role must not be null.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("A recorded reason is mandatory for role modification.");
        }

        String targetId = targetUserId.trim();
        databaseManager.executeInTransaction(conn -> {
            User targetUser = userRepository.findById(targetId, conn)
                    .orElseThrow(() -> new EntityNotFoundException("User", targetId));

            UserRole oldRole = targetUser.getRole();
            targetUser.setRole(newRole);
            targetUser.setUpdatedAt(LocalDateTime.now());
            userRepository.update(targetUser, conn);

            String actionId = IdGenerator.nextAdminActionId();
            String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
            String safeDetails = AdminAction.sanitizeWithoutSecrets("Changed role of user " + targetId + " from " + oldRole + " to " + newRole + ". Reason: " + safeReason);
            AdminAction action = new AdminAction(actionId, admin.getUserId(), "ROLE_CHANGE", targetId, safeReason, safeDetails);
            try {
                adminActionRepository.save(action, conn);
            } catch (Exception e) {
                throw new OtogException("Failed to record admin role change action: " + e.getMessage(), e);
            }
            return null;
        });
    }

    @Override
    public List<AdminAction> getAdminActions(String requesterUserId) {
        validateAdminCaller(requesterUserId, "view administrative audit logs");
        return adminActionRepository.findAll();
    }

    @Override
    public void directSetBalance(String requesterUserId, String walletId, BigDecimal newBalance) {
        throw new UnsupportedOperationException("Direct balance editing is strictly prohibited. All balance adjustments must occur via audited financial transactions with ledger double-entry reconciliation.");
    }
}
