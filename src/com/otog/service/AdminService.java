package com.otog.service;

import com.otog.model.AdminAction;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared service contract for administrative permissions, account suspensions,
 * system audits, and traceable actions.
 */
public interface AdminService {
    /**
     * Suspends a user account with a mandatory recorded reason.
     * Freezes associated wallet and logs the action without secrets.
     */
    void suspendUser(String adminUserId, String targetUserId, String reason);

    /**
     * Reactivates a suspended user account with a mandatory recorded reason.
     * Unfreezes associated wallet and logs the action without secrets.
     */
    void reactivateUser(String adminUserId, String targetUserId, String reason);

    /**
     * Retrieves all registered user accounts. Restricted to administrators.
     */
    List<User> getAllUsers(String requesterUserId);

    /**
     * Retrieves all wallet accounts across the system. Restricted to administrators.
     */
    List<Wallet> getAllWallets(String requesterUserId);

    /**
     * Retrieves all financial transactions across all accounts. Restricted to administrators.
     */
    List<Transaction> getAllTransactions(String requesterUserId);

    /**
     * Retrieves transactions for a specific user.
     * Administrators may inspect any user; ordinary users (USER/MERCHANT) may only inspect their own history.
     */
    List<Transaction> getUserTransactions(String requesterUserId, String targetUserId);

    /**
     * Retrieves the wallet for a specific user.
     * Administrators may inspect any wallet; ordinary users may only inspect their own wallet.
     */
    Wallet getUserWallet(String requesterUserId, String targetUserId);

    /**
     * Updates a user's role (e.g. promoting to MERCHANT). Restricted to administrators.
     */
    void changeUserRole(String adminUserId, String targetUserId, UserRole newRole, String reason);

    /**
     * Retrieves all recorded administrative actions. Restricted to administrators.
     */
    List<AdminAction> getAdminActions(String requesterUserId);

    /**
     * Direct balance editing is strictly prohibited by e-wallet financial integrity rules.
     * Always throws {@link UnsupportedOperationException}.
     */
    void directSetBalance(String requesterUserId, String walletId, BigDecimal newBalance);
}
