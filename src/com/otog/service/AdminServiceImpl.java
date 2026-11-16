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
        String effectiveAdminId = adminUserId;
        if (effectiveAdminId == null || effectiveAdminId.trim().isEmpty()) {
            SessionManager session = SessionManager.getInstance();
            if (session.isLoggedIn() && session.getCurrentUser() != null) {
                effectiveAdminId = session.getCurrentUser().getUserId();
            } else {
                throw new SecurityException("Access denied: Active administrator identity required to " + operationDescription + ".");
            }
        }

        String trimmedId = effectiveAdminId.trim();
        User caller = userRepository.findById(trimmedId)
                .orElseThrow(() -> new SecurityException("Access denied: Unknown user identity: " + trimmedId));

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

        User targetUser = userRepository.findById(targetId)
                .orElseThrow(() -> new EntityNotFoundException("User", targetId));

        targetUser.setStatus(UserStatus.SUSPENDED);
        targetUser.setUpdatedAt(LocalDateTime.now());
        userRepository.update(targetUser);

        // Freeze associated wallet
        walletRepository.findByUserId(targetId).ifPresent(wallet -> {
            wallet.setStatus(WalletStatus.FROZEN);
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepository.update(wallet);
        });

        // Terminate any active session for the suspended user
        SessionManager session = SessionManager.getInstance();
        if (session.isSessionActive(targetId)) {
            session.logout();
        }

        // Record traceable administrative action without secrets
        String actionId = IdGenerator.nextAdminActionId();
        String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
        String safeDetails = AdminAction.sanitizeWithoutSecrets("Suspended user " + targetId + " (" + targetUser.getName() + "). Reason: " + safeReason);
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "SUSPEND_USER", targetId, safeReason, safeDetails);
        adminActionRepository.save(action);
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

        User targetUser = userRepository.findById(targetId)
                .orElseThrow(() -> new EntityNotFoundException("User", targetId));

        targetUser.setStatus(UserStatus.ACTIVE);
        targetUser.setUpdatedAt(LocalDateTime.now());
        userRepository.update(targetUser);

        // Unfreeze associated wallet
        walletRepository.findByUserId(targetId).ifPresent(wallet -> {
            wallet.setStatus(WalletStatus.ACTIVE);
            wallet.setUpdatedAt(LocalDateTime.now());
            walletRepository.update(wallet);
        });

        // Reset failed login attempts and pin attempts upon admin reactivation
        credentialRepository.resetFailedAttempts(targetId);

        // Record traceable administrative action without secrets
        String actionId = IdGenerator.nextAdminActionId();
        String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
        String safeDetails = AdminAction.sanitizeWithoutSecrets("Reactivated user " + targetId + " (" + targetUser.getName() + "). Reason: " + safeReason);
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "REACTIVATE_USER", targetId, safeReason, safeDetails);
        adminActionRepository.save(action);
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
        if (requesterUserId == null || requesterUserId.trim().isEmpty()) {
            throw new SecurityException("Access denied: Requester identity required.");
        }
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String reqId = requesterUserId.trim();
        String tgtId = targetUserId.trim();

        User requester = userRepository.findById(reqId)
                .orElseThrow(() -> new SecurityException("Access denied: Unknown requester user: " + reqId));

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
        if (requesterUserId == null || requesterUserId.trim().isEmpty()) {
            throw new SecurityException("Access denied: Requester identity required.");
        }
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            throw new IllegalArgumentException("Target user ID must not be null or empty.");
        }
        String reqId = requesterUserId.trim();
        String tgtId = targetUserId.trim();

        User requester = userRepository.findById(reqId)
                .orElseThrow(() -> new SecurityException("Access denied: Unknown requester user: " + reqId));

        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new SecurityException("Access denied: Requester account is " + requester.getStatus() + ".");
        }

        if (requester.getRole() != UserRole.ADMIN && !reqId.equals(tgtId)) {
            throw new SecurityException("Access denied: Ordinary users cannot inspect another user's wallet.");
        }

        return walletRepository.findByUserId(tgtId)
                .orElseThrow(() -> new EntityNotFoundException("Wallet for user", tgtId));
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
        User targetUser = userRepository.findById(targetId)
                .orElseThrow(() -> new EntityNotFoundException("User", targetId));

        UserRole oldRole = targetUser.getRole();
        targetUser.setRole(newRole);
        targetUser.setUpdatedAt(LocalDateTime.now());
        userRepository.update(targetUser);

        String actionId = IdGenerator.nextAdminActionId();
        String safeReason = AdminAction.sanitizeWithoutSecrets(reason.trim());
        String safeDetails = AdminAction.sanitizeWithoutSecrets("Changed role of user " + targetId + " from " + oldRole + " to " + newRole + ". Reason: " + safeReason);
        AdminAction action = new AdminAction(actionId, admin.getUserId(), "ROLE_CHANGE", targetId, safeReason, safeDetails);
        adminActionRepository.save(action);
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
