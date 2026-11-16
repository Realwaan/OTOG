package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.*;
import com.otog.repository.AdminActionRepository;
import com.otog.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CAP-09 Test Suite — Admin permissions, account controls, and audit traceability:
 * 1. Seed an admin account and enforce User/Merchant/Admin permissions in services.
 * 2. View accounts/transactions and suspend/reactivate users with a recorded reason.
 * 3. Suspended accounts cannot initiate payments; record admin actions without secrets.
 * 4. Ordinary users cannot invoke admin operations or inspect another user's history; prohibit direct balance editing.
 */
public class AdminPermissionsAndAccountControlTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
    private AdminService adminService;
    private AdminActionRepository adminActionRepository;

    private User adminUser;
    private User regularUserAlice;
    private User regularUserBob;
    private User merchantUserCharlie;

    private Wallet aliceWallet;
    private Wallet bobWallet;
    private Wallet charlieWallet;

    @BeforeEach
    void setUp() {
        SessionManager.getInstance().reset();

        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = authService.getWalletService();
        adminService = walletService.getAdminService();
        adminActionRepository = walletService.getAdminActionRepository();

        // 1. Verify default admin was seeded automatically by DatabaseManager
        adminUser = walletService.getUserRepository().findById("USR-ADMIN-01")
                .orElseThrow(() -> new AssertionError("Default admin USR-ADMIN-01 must be automatically seeded"));

        // 2. Register regular users and merchant
        regularUserAlice = authService.registerUser("Alice Santos", "09171110001", "alice@otog.ph", "AlicePass123", UserRole.USER);
        regularUserBob = authService.registerUser("Bob Reyes", "09171110002", "bob@otog.ph", "BobPass123", UserRole.USER);
        merchantUserCharlie = authService.registerUser("Charlie Store", "09171110003", "charlie@otog.ph", "CharliePass123", UserRole.MERCHANT);

        aliceWallet = walletService.getWalletByUserId(regularUserAlice.getUserId());
        bobWallet = walletService.getWalletByUserId(regularUserBob.getUserId());
        charlieWallet = walletService.getWalletByUserId(merchantUserCharlie.getUserId());

        // Fund Alice and Bob's wallets for payment testing
        walletService.cashIn("FUND-ALICE", aliceWallet.getWalletId(), null, new BigDecimal("2000.00"), "Alice deposit");
        walletService.cashIn("FUND-BOB", bobWallet.getWalletId(), null, new BigDecimal("1000.00"), "Bob deposit");
    }

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    @Test
    @DisplayName("Criterion 1: Seed admin account and enforce User/Merchant/Admin permissions")
    void testSeedAdminAccountAndRoleEnforcement() {
        // Verify seeded admin properties
        assertEquals("USR-ADMIN-01", adminUser.getUserId());
        assertEquals("admin", adminUser.getName());
        assertEquals(UserRole.ADMIN, adminUser.getRole());
        assertEquals(UserStatus.ACTIVE, adminUser.getStatus());
        assertTrue(adminUser.isAdmin());

        // Admin can successfully authenticate with seeded credentials
        Optional<User> authenticatedAdmin = authService.authenticatePassword("admin", "password");
        assertTrue(authenticatedAdmin.isPresent());
        assertEquals("USR-ADMIN-01", authenticatedAdmin.get().getUserId());

        // Prohibit public self-registration as ADMIN
        OtogException regEx = assertThrows(OtogException.class, () ->
                authService.registerUser("Fake Admin", "09179999999", "fake@otog.ph", "Hacker123", UserRole.ADMIN));
        assertTrue(regEx.getMessage().contains("Self-registration as ADMIN is prohibited"));

        // Merchant registration is allowed and reflected in role
        assertEquals(UserRole.MERCHANT, merchantUserCharlie.getRole());
        assertTrue(merchantUserCharlie.isMerchant());
        assertFalse(merchantUserCharlie.isAdmin());

        // Only Admin can promote/change roles with a recorded reason
        assertThrows(SecurityException.class, () ->
                adminService.changeUserRole(regularUserAlice.getUserId(), regularUserBob.getUserId(), UserRole.MERCHANT, "Promotion attempt by peer"));

        assertDoesNotThrow(() ->
                adminService.changeUserRole(adminUser.getUserId(), regularUserBob.getUserId(), UserRole.MERCHANT, "Promoted Bob to Merchant after business KYC approval"));

        User updatedBob = walletService.getUserRepository().findById(regularUserBob.getUserId()).orElseThrow();
        assertEquals(UserRole.MERCHANT, updatedBob.getRole());
    }

    @Test
    @DisplayName("Criterion 2: View accounts/transactions and suspend/reactivate users with recorded reason")
    void testViewAccountsAndSuspendReactivateWithRecordedReason() {
        // Admin views accounts and transactions
        List<User> allUsers = adminService.getAllUsers(adminUser.getUserId());
        assertTrue(allUsers.size() >= 4, "Admin must be able to view all user accounts");

        List<Wallet> allWallets = adminService.getAllWallets(adminUser.getUserId());
        assertTrue(allWallets.size() >= 4, "Admin must be able to view all wallet accounts");

        List<Transaction> allTransactions = adminService.getAllTransactions(adminUser.getUserId());
        assertFalse(allTransactions.isEmpty(), "Admin must be able to view all system transactions");

        // Suspend user requires a non-empty reason
        IllegalArgumentException emptyReasonEx = assertThrows(IllegalArgumentException.class, () ->
                adminService.suspendUser(adminUser.getUserId(), regularUserAlice.getUserId(), "   "));
        assertTrue(emptyReasonEx.getMessage().contains("recorded reason is mandatory"));

        // Admin suspends Alice with a valid recorded reason
        String suspendReason = "Suspected fraudulent cash-in chargeback attempt";
        adminService.suspendUser(adminUser.getUserId(), regularUserAlice.getUserId(), suspendReason);

        User suspendedAlice = walletService.getUserRepository().findById(regularUserAlice.getUserId()).orElseThrow();
        assertEquals(UserStatus.SUSPENDED, suspendedAlice.getStatus());
        assertTrue(suspendedAlice.isSuspended());

        Wallet frozenAliceWallet = walletService.getWallet(aliceWallet.getWalletId());
        assertEquals(WalletStatus.FROZEN, frozenAliceWallet.getStatus(), "Wallet must be frozen upon user suspension");

        // Reactivate user requires a non-empty reason
        assertThrows(IllegalArgumentException.class, () ->
                adminService.reactivateUser(adminUser.getUserId(), regularUserAlice.getUserId(), ""));

        // Admin reactivates Alice with a recorded reason
        String reactivateReason = "Identity verification and bank statement confirmed legitimate";
        adminService.reactivateUser(adminUser.getUserId(), regularUserAlice.getUserId(), reactivateReason);

        User reactivatedAlice = walletService.getUserRepository().findById(regularUserAlice.getUserId()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, reactivatedAlice.getStatus());
        assertTrue(reactivatedAlice.isActive());

        Wallet unfrozenAliceWallet = walletService.getWallet(aliceWallet.getWalletId());
        assertEquals(WalletStatus.ACTIVE, unfrozenAliceWallet.getStatus(), "Wallet must be active upon user reactivation");
    }

    @Test
    @DisplayName("Criterion 3: Suspended accounts cannot initiate payments; record admin actions without secrets")
    void testSuspendedAccountsCannotInitiatePaymentsAndActionsRecordedWithoutSecrets() {
        // Suspend Bob
        adminService.suspendUser(adminUser.getUserId(), regularUserBob.getUserId(), "Suspicious rapid transactions");

        // 1. Direct wallet service rejects payment initiation from suspended user
        WalletService ungatedWalletService = new WalletService(databaseManager);
        WalletException transferEx = assertThrows(WalletException.class, () ->
                ungatedWalletService.transfer("TX-SUSPEND-01", bobWallet.getWalletId(), aliceWallet.getWalletId(),
                        new BigDecimal("100.00"), BigDecimal.ZERO, "Payment from suspended user"));
        assertTrue(transferEx.getMessage().contains("Suspended accounts cannot initiate payments"));

        // Suspended user cannot initiate cash out
        WalletException cashOutEx = assertThrows(WalletException.class, () ->
                ungatedWalletService.cashOut("TX-SUSPEND-02", bobWallet.getWalletId(), null,
                        new BigDecimal("50.00"), BigDecimal.ZERO, "Withdrawal from suspended user"));
        assertTrue(cashOutEx.getMessage().contains("Suspended accounts cannot initiate payments"));

        // 2. Suspended user session is rejected in SessionManager
        User suspendedBob = walletService.getUserRepository().findById(regularUserBob.getUserId()).orElseThrow();
        SessionManager.getInstance().login(suspendedBob, bobWallet);
        SessionManager.getInstance().markPinVerified();

        WalletException sessionPaymentEx = assertThrows(WalletException.class, () ->
                walletService.transfer("TX-SUSPEND-03", bobWallet.getWalletId(), aliceWallet.getWalletId(),
                        new BigDecimal("50.00"), BigDecimal.ZERO, "Session payment from suspended user"));
        assertTrue(sessionPaymentEx.getMessage().contains("Suspended accounts cannot initiate payments"));

        // 3. Suspended user cannot authenticate to establish a new session
        OtogException loginEx = assertThrows(OtogException.class, () ->
                authService.authenticatePassword("09171110002", "BobPass123"));
        assertTrue(loginEx.getMessage().contains("Account is suspended"));

        // Verify traceable administrative actions are recorded
        List<AdminAction> actions = adminService.getAdminActions(adminUser.getUserId());
        assertFalse(actions.isEmpty(), "Admin actions must be persisted");

        // Verify actions record reasons and details WITHOUT secrets
        for (AdminAction action : actions) {
            assertNotNull(action.getActionId());
            assertNotNull(action.getAdminUserId());
            assertNotNull(action.getCreatedAt());

            String details = action.getDetails();
            String reason = action.getReason();

            if (details != null) {
                assertFalse(details.toLowerCase().contains("password="), "Details must never contain raw passwords");
                assertFalse(details.toLowerCase().contains("pin="), "Details must never contain raw PINs");
                assertFalse(details.toLowerCase().contains("salt="), "Details must never contain credential salts");
            }
            if (reason != null) {
                assertFalse(reason.toLowerCase().contains("password="), "Reason must never contain secrets");
                assertFalse(reason.toLowerCase().contains("pin="), "Reason must never contain secrets");
            }
        }

        // Test sanitizer directly against accidental secret injection
        String leakedString = "User action password=supersecret pin=9876 salt=abc123 token=xyz999";
        String sanitized = AdminAction.sanitizeWithoutSecrets(leakedString);
        assertFalse(sanitized.contains("supersecret"));
        assertFalse(sanitized.contains("9876"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("xyz999"));
        assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Criterion 4: Ordinary users cannot invoke admin operations or inspect another user's history; prohibit direct balance editing")
    void testOrdinaryUsersCannotInvokeAdminOperationsOrInspectOtherHistoryAndNoDirectBalanceEdit() {
        // Ordinary user (Alice) cannot suspend accounts
        SecurityException aliceSuspendEx = assertThrows(SecurityException.class, () ->
                adminService.suspendUser(regularUserAlice.getUserId(), regularUserBob.getUserId(), "Peer revenge suspension"));
        assertTrue(aliceSuspendEx.getMessage().contains("Administrator role required"));

        // Merchant (Charlie) cannot suspend accounts
        SecurityException charlieSuspendEx = assertThrows(SecurityException.class, () ->
                adminService.suspendUser(merchantUserCharlie.getUserId(), regularUserBob.getUserId(), "Merchant suspending customer"));
        assertTrue(charlieSuspendEx.getMessage().contains("Administrator role required"));

        // Ordinary user cannot view all users or all transactions
        assertThrows(SecurityException.class, () -> adminService.getAllUsers(regularUserAlice.getUserId()));
        assertThrows(SecurityException.class, () -> adminService.getAllWallets(regularUserAlice.getUserId()));
        assertThrows(SecurityException.class, () -> adminService.getAllTransactions(regularUserAlice.getUserId()));
        assertThrows(SecurityException.class, () -> adminService.getAdminActions(regularUserAlice.getUserId()));

        // Ordinary user CAN view their OWN transaction history
        List<Transaction> aliceOwnTxns = adminService.getUserTransactions(regularUserAlice.getUserId(), regularUserAlice.getUserId());
        assertNotNull(aliceOwnTxns);

        List<Transaction> aliceOwnHistory = walletService.getTransactionHistory(regularUserAlice.getUserId(), aliceWallet.getWalletId());
        assertNotNull(aliceOwnHistory);

        // Ordinary user CANNOT inspect another user's transaction history
        SecurityException inspectHistoryEx = assertThrows(SecurityException.class, () ->
                adminService.getUserTransactions(regularUserAlice.getUserId(), regularUserBob.getUserId()));
        assertTrue(inspectHistoryEx.getMessage().contains("Ordinary users cannot inspect another user's transaction history"));

        SecurityException inspectWalletHistoryEx = assertThrows(SecurityException.class, () ->
                walletService.getTransactionHistory(regularUserAlice.getUserId(), bobWallet.getWalletId()));
        assertTrue(inspectWalletHistoryEx.getMessage().contains("Ordinary users cannot inspect another user's transaction history"));

        // Merchant cannot inspect a user's transaction history
        assertThrows(SecurityException.class, () ->
                adminService.getUserTransactions(merchantUserCharlie.getUserId(), regularUserAlice.getUserId()));

        // Admin CAN inspect any user's transaction history
        List<Transaction> adminInspectedAliceTxns = adminService.getUserTransactions(adminUser.getUserId(), regularUserAlice.getUserId());
        assertNotNull(adminInspectedAliceTxns);
        List<Transaction> adminInspectedWalletTxns = walletService.getTransactionHistory(adminUser.getUserId(), aliceWallet.getWalletId());
        assertNotNull(adminInspectedWalletTxns);

        // Direct balance editing is strictly prohibited
        UnsupportedOperationException directEditEx = assertThrows(UnsupportedOperationException.class, () ->
                walletService.directSetBalance(adminUser.getUserId(), aliceWallet.getWalletId(), new BigDecimal("999999.00")));
        assertTrue(directEditEx.getMessage().contains("Direct balance editing is strictly prohibited"));

        assertThrows(UnsupportedOperationException.class, () ->
                adminService.directSetBalance(regularUserAlice.getUserId(), aliceWallet.getWalletId(), new BigDecimal("999999.00")));
    }

    @Test
    @DisplayName("Edge Case: Admin cannot suspend their own account and inactive admin cannot act")
    void testAdminSelfSuspensionAndInactiveAdminBlocked() {
        // Admin cannot suspend self
        OtogException selfEx = assertThrows(OtogException.class, () ->
                adminService.suspendUser(adminUser.getUserId(), adminUser.getUserId(), "Suicide suspension"));
        assertTrue(selfEx.getMessage().contains("cannot suspend their own account"));

        // Create an inactive admin to test authorization gate
        User inactiveAdmin = new User("USR-ADMIN-INACTIVE", "Inactive Admin", "09000000001",
                "inactive@otog.ph", UserRole.ADMIN, UserStatus.SUSPENDED);
        walletService.getUserRepository().save(inactiveAdmin);

        SecurityException blockedEx = assertThrows(SecurityException.class, () ->
                adminService.suspendUser(inactiveAdmin.getUserId(), regularUserAlice.getUserId(), "Attempt from suspended admin"));
        assertTrue(blockedEx.getMessage().contains("Administrator account is SUSPENDED"));
    }

    @Test
    @DisplayName("Traceable administrative actions log review and suspension details in SQLite")
    void testAdminActionTraceabilityPersistence() {
        adminService.suspendUser(adminUser.getUserId(), regularUserBob.getUserId(), "High risk account activity");

        List<AdminAction> bobActions = adminActionRepository.findByTargetUserId(regularUserBob.getUserId());
        assertEquals(1, bobActions.size());

        AdminAction action = bobActions.get(0);
        assertEquals("SUSPEND_USER", action.getActionType());
        assertEquals("High risk account activity", action.getReason());
        assertEquals(adminUser.getUserId(), action.getAdminUserId());
        assertEquals(regularUserBob.getUserId(), action.getTargetUserId());
        assertTrue(action.getDetails().contains("Suspended user " + regularUserBob.getUserId()));
    }
}
