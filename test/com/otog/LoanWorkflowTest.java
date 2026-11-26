package com.otog;

import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.WalletException;
import com.otog.model.AdminAction;
import com.otog.model.LedgerEntry;
import com.otog.model.LedgerEntryType;
import com.otog.model.LoanStatus;
import com.otog.model.LoanTransaction;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.LoanService;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.LoanPanel;
import com.otog.ui.Main;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests CAP-12: Basic simulated loan workflow:
 * - Single configured loan product with documented interest/fee formula & total repayment preview
 * - Bound IntelliJ Swing LoanPanel.form initialization & role-gated Admin Loan Approvals sub-tab
 * - User application -> Admin approve/reject -> single disbursement crediting wallet exactly once
 * - Amount due display & atomic full lump-sum repayment
 * - Rejection of duplicate disbursement, partial repayment, insufficient funds, and repeat repayment
 * - Restart persistence, proxy isolation, concurrency safety, and notifications
 */
public class LoanWorkflowTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
    private Main activeDashboard;

    @BeforeEach
    void setUp() {
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = new WalletService(databaseManager, authService);
        SessionManager.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        if (activeDashboard != null) {
            activeDashboard.dispose();
            activeDashboard = null;
        }
        SessionManager.getInstance().reset();
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
        databaseManager.close();
    }

    @Test
    void testConfiguredLoanProductDocumentedFormulaSingleProductPolicyAndBoundPanel() {
        LoanService.LoanProduct product = walletService.getConfiguredLoanProduct();
        assertEquals("OTOG-SIM-LOAN", product.getProductCode());
        assertEquals("OTOG Simulated Quick Cash Loan", product.getProductName());
        assertEquals(new BigDecimal("500.00"), product.getMinPrincipal());
        assertEquals(new BigDecimal("10000.00"), product.getMaxPrincipal());
        assertEquals(0, new BigDecimal("0.03").compareTo(product.getMonthlyInterestRate()));
        assertEquals(3, product.getTermMonths());
        assertEquals(new BigDecimal("25.00"), product.getProcessingFeeFlat());
        assertEquals(0, new BigDecimal("0.01").compareTo(product.getProcessingFeePercent()));
        assertEquals("PHP", product.getCurrency());
        assertTrue(product.getCalculationFormula().contains("Interest"));
        assertTrue(product.getCalculationFormula().contains("Processing Fee"));
        assertTrue(product.getCalculationFormula().contains("Total Repayment"));
        assertTrue(product.getScopeDisclaimer().contains("installment scheduling"));
        assertTrue(product.getScopeDisclaimer().contains("partial repayment"));

        LoanPanel panel = new LoanPanel();
        assertNotNull(panel.getRootPanel());
        assertInstanceOf(GridLayoutManager.class, panel.getRootPanel().getLayout());
        assertTrue(panel.getProductNameLabel().getText().contains("SIMULATED"));
        assertNotNull(panel.getFormulaSummaryLabel().getText());
        assertNotNull(panel.getUserLoansTableModel());
        assertSame(panel.getUserLoansTableModel(), panel.getUserLoansTable().getModel());
        assertNotNull(panel.getAdminLoansTableModel());
        assertSame(panel.getAdminLoansTableModel(), panel.getAdminLoansTable().getModel());

        User borrower = authService.registerUser("Lina Cruz", "09173000001", "lina@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(borrower.getUserId());

        // Principal bounds and precision validation
        assertThrows(InvalidAmountException.class, () ->
                walletService.previewLoanApplication(wallet.getWalletId(), new BigDecimal("499.99")));
        assertThrows(InvalidAmountException.class, () ->
                walletService.previewLoanApplication(wallet.getWalletId(), new BigDecimal("10000.01")));
        assertThrows(InvalidAmountException.class, () ->
                walletService.previewLoanApplication(wallet.getWalletId(), new BigDecimal("0.00")));
        assertThrows(InvalidAmountException.class, () ->
                walletService.previewLoanApplication(wallet.getWalletId(), new BigDecimal("1000.005")));

        // Single configured product policy rejects custom rates or custom installment terms
        WalletException badRateEx = assertThrows(WalletException.class, () ->
                walletService.applyLoan("REQ-LN-BADRATE", wallet.getWalletId(),
                        new BigDecimal("1000.00"), new BigDecimal("0.10"), 3));
        assertTrue(badRateEx.getMessage().contains("single configured loan product"));

        WalletException badTermEx = assertThrows(WalletException.class, () ->
                walletService.applyLoan("REQ-LN-BADTERM", wallet.getWalletId(),
                        new BigDecimal("1000.00"), new BigDecimal("0.03"), 6));
        assertTrue(badTermEx.getMessage().contains("installment scheduling is deferred"));
    }

    @Test
    void testPreviewApplyAdminApproveSingleDisbursementAndFullRepaymentLifecycle() {
        User borrower = authService.registerUser("Marco Santos", "09173000002", "marco.loan@otog.ph", "Secret123");
        Wallet borrowerWallet = walletService.getWalletByUserId(borrower.getUserId());
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = walletService.getWalletByUserId(admin.getUserId());

        SessionManager.getInstance().login(borrower, borrowerWallet);
        authService.setPin(borrower.getUserId(), "Secret123", "1234");

        // 1. Preview for PHP 1,000.00:
        //    Interest = 1000 * 0.03 * 3 = 90.00
        //    Processing Fee = 25.00 + (1000 * 0.01 = 10.00) = 35.00
        //    Total Repayment = 1000.00 + 90.00 + 35.00 = 1125.00
        LoanService.LoanPreview preview = walletService.previewLoanApplication(
                borrowerWallet.getWalletId(), new BigDecimal("1000.00"));
        assertEquals(new BigDecimal("1000.00"), preview.getPrincipal());
        assertEquals(new BigDecimal("90.00"), preview.getInterestAmount());
        assertEquals(new BigDecimal("35.00"), preview.getProcessingFee());
        assertEquals(new BigDecimal("1125.00"), preview.getTotalRepaymentDue());
        assertTrue(preview.toConfirmationText().contains("PHP 1,125.00"));
        assertTrue(preview.toConfirmationText().contains("[SIMULATED]"));

        // 2. User applies — no balance change, no ledger rows, status PENDING_APPROVAL
        LoanTransaction applied = walletService.applyForLoan(
                "REQ-LN-APP-1", borrowerWallet.getWalletId(), new BigDecimal("1000.00"));
        assertNotNull(applied.getLoanId());
        assertEquals(LoanStatus.PENDING_APPROVAL, applied.getLoanStatus());
        assertEquals(TransactionStatus.PENDING, applied.getStatus());
        assertEquals(new BigDecimal("1125.00"), applied.getTotalRepaymentDue());
        assertEquals(new BigDecimal("0.00"), applied.getAmountDue());
        assertEquals(new BigDecimal("0.00"), walletService.getBalance(borrowerWallet.getWalletId()));
        assertTrue(walletService.getLedgerRepository().findByWalletId(borrowerWallet.getWalletId()).isEmpty());

        // Duplicate application reference rejected
        assertThrows(DuplicateTransactionException.class, () ->
                walletService.applyForLoan(" REQ-LN-APP-1 ", borrowerWallet.getWalletId(), new BigDecimal("1000.00")));

        // Ordinary user cannot approve, reject, or inspect all loan applications
        assertThrows(SecurityException.class, () -> walletService.approveLoan(applied.getLoanId()));
        assertThrows(SecurityException.class, () -> walletService.rejectLoan(borrower.getUserId(), applied.getLoanId(), "Self"));
        assertThrows(SecurityException.class, () -> walletService.getAllLoanApplications(borrower.getUserId()));

        // Borrower cannot repay a pending (unapproved) loan
        assertThrows(WalletException.class, () ->
                walletService.repayLoanFullWithPin("REQ-LN-PREPAY", borrowerWallet.getWalletId(), applied.getLoanId(), "1234"));

        // 3. Admin approves -> credits wallet EXACTLY ONCE (+1000.00), records ledger + audit + notification
        SessionManager.getInstance().login(admin, adminWallet);
        List<LoanTransaction> queue = walletService.getAllLoanApplications(admin.getUserId());
        assertEquals(1, queue.size());
        assertEquals(applied.getLoanId(), queue.get(0).getLoanId());

        LoanTransaction approved = walletService.approveLoan(admin.getUserId(), applied.getLoanId(), "Verified demo borrower");
        assertEquals(LoanStatus.DISBURSED, approved.getLoanStatus());
        assertEquals(TransactionStatus.COMPLETED, approved.getStatus());
        assertEquals(new BigDecimal("1125.00"), approved.getAmountDue());
        assertNotNull(approved.getDisbursedAt());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(borrowerWallet.getWalletId()));

        List<LedgerEntry> ledgerAfterDisburse = walletService.getLedgerRepository().findByWalletId(borrowerWallet.getWalletId());
        assertEquals(1, ledgerAfterDisburse.size());
        assertEquals(LedgerEntryType.CREDIT, ledgerAfterDisburse.get(0).getEntryType());
        assertEquals(new BigDecimal("1000.00"), ledgerAfterDisburse.get(0).getAmount());

        List<AdminAction> actions = walletService.getAdminActions(admin.getUserId());
        assertTrue(actions.stream().anyMatch(a -> "APPROVE_LOAN".equals(a.getActionType())
                && borrower.getUserId().equals(a.getTargetUserId())));

        // 4. Reject duplicate disbursement (by loanId, transactionId, and referenceNumber)
        WalletException dup1 = assertThrows(WalletException.class, () -> walletService.approveLoan(applied.getLoanId()));
        assertTrue(dup1.getMessage().contains("Duplicate disbursement"));
        assertThrows(WalletException.class, () -> walletService.approveLoan(admin.getUserId(), applied.getTransactionId(), "Retry"));
        assertThrows(WalletException.class, () -> walletService.approveLoan(admin.getUserId(), applied.getReferenceNumber(), "Retry"));
        assertThrows(WalletException.class, () -> walletService.rejectLoan(admin.getUserId(), applied.getLoanId(), "Too late"));
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(borrowerWallet.getWalletId()));
        assertEquals(1, walletService.getLedgerRepository().findByWalletId(borrowerWallet.getWalletId()).size());

        // 5. Borrower checks amount due, receipt, partial repayment rejection, insufficient funds rejection
        SessionManager.getInstance().login(borrower, walletService.getWallet(borrowerWallet.getWalletId()));
        assertEquals(new BigDecimal("1125.00"), walletService.getOutstandingAmountDue(borrowerWallet.getWalletId()));

        String disburseReceipt = walletService.getTransactionReceipt(borrowerWallet.getWalletId(), applied.getReferenceNumber());
        assertTrue(disburseReceipt.contains("OTOG Lending"));
        assertTrue(disburseReceipt.contains("PHP 1,000.00"));
        assertTrue(disburseReceipt.contains("PHP 1,125.00"));

        // Partial repayment rejected (deferred)
        WalletException partialEx = assertThrows(WalletException.class, () ->
                walletService.repayLoanWithPin("REQ-LN-PARTIAL", borrowerWallet.getWalletId(),
                        applied.getLoanId(), new BigDecimal("500.00"), "1234"));
        assertTrue(partialEx.getMessage().contains("Partial repayment is deferred"));

        // Overpayment rejected
        assertThrows(WalletException.class, () ->
                walletService.repayLoanWithPin("REQ-LN-OVER", borrowerWallet.getWalletId(),
                        applied.getLoanId(), new BigDecimal("1200.00"), "1234"));

        // Insufficient funds full repayment rejected (wallet has 1000.00 < 1125.00 due)
        assertThrows(InsufficientFundsException.class, () ->
                walletService.repayLoanWithPin("REQ-LN-NSF", borrowerWallet.getWalletId(),
                        applied.getLoanId(), new BigDecimal("1125.00"), "1234"));
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(borrowerWallet.getWalletId()));
        assertEquals(new BigDecimal("1125.00"), walletService.getOutstandingAmountDue(borrowerWallet.getWalletId()));

        // 6. Borrower tops up PHP 200.00 -> balance 1200.00 -> repays PHP 1,125.00 in full atomically
        walletService.cashIn("REQ-LN-TOPUP", borrowerWallet.getWalletId(), null, new BigDecimal("200.00"), "Top up for loan");
        assertEquals(new BigDecimal("1200.00"), walletService.getBalance(borrowerWallet.getWalletId()));

        LoanTransaction repaid = walletService.repayLoanWithPin(
                "REQ-LN-REPAY-1", borrowerWallet.getWalletId(), applied.getLoanId(), new BigDecimal("1125.00"), "1234");
        assertEquals(TransactionType.LOAN_REPAYMENT, repaid.getType());
        assertEquals(TransactionStatus.COMPLETED, repaid.getStatus());
        assertEquals(LoanStatus.PAID, repaid.getLoanStatus());
        assertEquals(new BigDecimal("1000.00"), repaid.getAmount());
        assertEquals(new BigDecimal("125.00"), repaid.getFee());
        assertEquals(new BigDecimal("1125.00"), repaid.getTotalAmount());
        assertEquals(new BigDecimal("0.00"), repaid.getAmountDue());
        assertEquals(new BigDecimal("75.00"), walletService.getBalance(borrowerWallet.getWalletId()));
        assertEquals(new BigDecimal("0.00"), walletService.getOutstandingAmountDue(borrowerWallet.getWalletId()));

        List<LoanTransaction> userLoansAfterPay = walletService.getLoansByWalletId(borrowerWallet.getWalletId());
        assertEquals(1, userLoansAfterPay.size());
        assertEquals(LoanStatus.PAID, userLoansAfterPay.get(0).getLoanStatus());
        assertEquals(new BigDecimal("0.00"), userLoansAfterPay.get(0).getAmountDue());
        assertEquals(repaid.getTransactionId(), userLoansAfterPay.get(0).getRepaymentTransactionId());

        List<LedgerEntry> ledgerAfterRepay = walletService.getLedgerRepository().findByWalletId(borrowerWallet.getWalletId());
        assertTrue(ledgerAfterRepay.stream().anyMatch(e ->
                e.getEntryType() == LedgerEntryType.DEBIT
                        && e.getAmount().compareTo(new BigDecimal("1125.00")) == 0
                        && repaid.getTransactionId().equals(e.getTransactionId())));

        // Verify post-commit notifications across full lifecycle
        List<Notification> notifs = walletService.getNotifications(borrower.getUserId(), false);
        assertTrue(notifs.stream().anyMatch(n -> n.getType() == NotificationType.LOAN_APPLIED));
        assertTrue(notifs.stream().anyMatch(n -> n.getType() == NotificationType.LOAN_APPROVED));
        assertTrue(notifs.stream().anyMatch(n -> n.getType() == NotificationType.LOAN_REPAID));

        // 7. Reject repeat repayment of an already PAID loan (even with fresh ref & ample balance)
        walletService.cashIn("REQ-LN-EXTRA", borrowerWallet.getWalletId(), null, new BigDecimal("2000.00"), "Extra funds");
        WalletException repeatEx = assertThrows(WalletException.class, () ->
                walletService.repayLoanFull("REQ-LN-REPAY-REPEAT", borrowerWallet.getWalletId(), applied.getLoanId()));
        assertTrue(repeatEx.getMessage().contains("Repeat repayment rejected"));
        assertEquals(new BigDecimal("2075.00"), walletService.getBalance(borrowerWallet.getWalletId()));

        // Reject duplicate disbursement on PAID loan too
        SessionManager.getInstance().login(admin, adminWallet);
        WalletException dupPaidEx = assertThrows(WalletException.class, () ->
                walletService.approveLoan(admin.getUserId(), applied.getLoanId(), "Double disburse"));
        assertTrue(dupPaidEx.getMessage().contains("Duplicate disbursement"));
        assertEquals(new BigDecimal("2075.00"), walletService.getBalance(borrowerWallet.getWalletId()));
    }

    @Test
    void testAdminRejectionDoesNotDisburseAuditsWithoutSecretsAndBlocksApprovalOrRepayment() {
        User borrower = authService.registerUser("Carla Lim", "09173000003", "carla.loan@otog.ph", "Secret123");
        Wallet borrowerWallet = walletService.getWalletByUserId(borrower.getUserId());
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = walletService.getWalletByUserId(admin.getUserId());

        LoanTransaction applied = walletService.applyForLoan(
                "REQ-LN-REJ-1", borrowerWallet.getWalletId(), new BigDecimal("2000.00"));

        SessionManager.getInstance().login(admin, adminWallet);
        // Blank rejection reason rejected
        assertThrows(IllegalArgumentException.class, () ->
                walletService.rejectLoan(admin.getUserId(), applied.getLoanId(), "   "));

        // Reject with secret-like text -> sanitized in audit log & loan record
        LoanTransaction rejected = walletService.rejectLoan(
                admin.getUserId(), applied.getLoanId(), "Incomplete verification pin=9876");
        assertEquals(LoanStatus.REJECTED, rejected.getLoanStatus());
        assertEquals(TransactionStatus.FAILED, rejected.getStatus());
        assertEquals(new BigDecimal("0.00"), rejected.getAmountDue());
        assertFalse(rejected.getDecisionReason().contains("9876"));
        assertEquals(new BigDecimal("0.00"), walletService.getBalance(borrowerWallet.getWalletId()));
        assertTrue(walletService.getLedgerRepository().findByWalletId(borrowerWallet.getWalletId()).isEmpty());

        List<AdminAction> auditLog = walletService.getAdminActions(admin.getUserId());
        AdminAction rejectAction = auditLog.stream()
                .filter(a -> "REJECT_LOAN".equals(a.getActionType()))
                .findFirst()
                .orElseThrow();
        assertFalse(rejectAction.getReason().contains("9876"));
        assertFalse(rejectAction.getDetails().contains("9876"));

        // Cannot approve or re-reject a rejected loan
        assertThrows(WalletException.class, () -> walletService.approveLoan(admin.getUserId(), applied.getLoanId(), "Retry"));
        assertThrows(WalletException.class, () -> walletService.rejectLoan(admin.getUserId(), applied.getLoanId(), "Again"));

        // Borrower notified and cannot repay rejected loan
        List<Notification> notifs = walletService.getNotifications(borrower.getUserId(), false);
        assertTrue(notifs.stream().anyMatch(n -> n.getType() == NotificationType.LOAN_REJECTED));

        SessionManager.getInstance().login(borrower, borrowerWallet);
        authService.setPin(borrower.getUserId(), "Secret123", "1234");
        assertThrows(WalletException.class, () ->
                walletService.repayLoanFullWithPin("REQ-LN-REPAY-REJ", borrowerWallet.getWalletId(), applied.getLoanId(), "1234"));
    }

    @Test
    void testConcurrentApprovalAndConcurrentRepaymentEnforceSingleDisbursementAndSingleDebit() throws Exception {
        WalletService noPinSvc = new WalletService(databaseManager);
        User borrower = authService.registerUser("Race Borrower", "09173000004", "race.loan@otog.ph", "Secret123");
        Wallet borrowerWallet = noPinSvc.getWalletByUserId(borrower.getUserId());
        User admin = noPinSvc.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = noPinSvc.getWalletByUserId(admin.getUserId());

        LoanTransaction applied = noPinSvc.applyForLoan(
                "REQ-LN-RACE-APP", borrowerWallet.getWalletId(), new BigDecimal("1000.00"));

        SessionManager.getInstance().login(admin, adminWallet);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch approveLatch = new CountDownLatch(1);
            AtomicInteger approveSuccess = new AtomicInteger();
            List<Future<?>> approveFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                approveFutures.add(pool.submit(() -> {
                    approveLatch.await();
                    try {
                        noPinSvc.approveLoan(admin.getUserId(), applied.getLoanId(), "Concurrent approval");
                        approveSuccess.incrementAndGet();
                    } catch (WalletException ignored) {
                    }
                    return null;
                }));
            }
            approveLatch.countDown();
            for (Future<?> f : approveFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, approveSuccess.get(), "Exactly one approval must disburse funds");
            assertEquals(new BigDecimal("1000.00"), noPinSvc.getBalance(borrowerWallet.getWalletId()));

            // Add ample funds so concurrent repayments cannot fail due to NSF
            SessionManager.getInstance().reset();
            noPinSvc.cashIn("REQ-LN-RACE-FUND", borrowerWallet.getWalletId(), null, new BigDecimal("4000.00"), "Seed");
            assertEquals(new BigDecimal("5000.00"), noPinSvc.getBalance(borrowerWallet.getWalletId()));

            CountDownLatch repayLatch = new CountDownLatch(1);
            AtomicInteger repaySuccess = new AtomicInteger();
            List<Future<?>> repayFutures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                repayFutures.add(pool.submit(() -> {
                    repayLatch.await();
                    try {
                        noPinSvc.repayLoanFull("REQ-LN-RACE-REP-" + idx, borrowerWallet.getWalletId(), applied.getLoanId());
                        repaySuccess.incrementAndGet();
                    } catch (WalletException ignored) {
                    }
                    return null;
                }));
            }
            repayLatch.countDown();
            for (Future<?> f : repayFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, repaySuccess.get(), "Exactly one full repayment must debit funds");
            assertEquals(new BigDecimal("3875.00"), noPinSvc.getBalance(borrowerWallet.getWalletId()));
            assertEquals(new BigDecimal("0.00"), noPinSvc.getOutstandingAmountDue(borrowerWallet.getWalletId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void testRestartPersistenceAndCrossAccountProxyIsolation(@TempDir Path tempDir) throws Exception {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("otog-loan-persist.db").toString().replace("\\", "/");
        String aliceWalletId;
        String bobWalletId;
        String aliceLoanId;
        String bobLoanId;

        try (DatabaseManager db1 = new DatabaseManager(dbUrl)) {
            AuthServiceImpl auth1 = new AuthServiceImpl(db1);
            WalletService ws1 = new WalletService(db1, auth1);
            User alice = auth1.registerUser("Alice Loan", "09173000010", "alice.ln@otog.ph", "Secret123");
            User bob = auth1.registerUser("Bob Loan", "09173000011", "bob.ln@otog.ph", "Secret123");
            User admin = ws1.getUserRepository().findById("USR-ADMIN-01").orElseThrow();

            aliceWalletId = ws1.getWalletByUserId(alice.getUserId()).getWalletId();
            bobWalletId = ws1.getWalletByUserId(bob.getUserId()).getWalletId();

            LoanTransaction aliceApp = ws1.applyForLoan("REQ-PERSIST-LN-A", aliceWalletId, new BigDecimal("1000.00"));
            LoanTransaction bobApp = ws1.applyForLoan("REQ-PERSIST-LN-B", bobWalletId, new BigDecimal("2000.00"));
            aliceLoanId = aliceApp.getLoanId();
            bobLoanId = bobApp.getLoanId();

            SessionManager.getInstance().login(admin, ws1.getWalletByUserId(admin.getUserId()));
            ws1.approveLoan(admin.getUserId(), aliceLoanId, "Approved Alice");
            ws1.rejectLoan(admin.getUserId(), bobLoanId, "Rejected Bob");
            SessionManager.getInstance().reset();
        }

        try (DatabaseManager db2 = new DatabaseManager(dbUrl)) {
            AuthServiceImpl auth2 = new AuthServiceImpl(db2);
            WalletService ws2 = new WalletService(db2, auth2);
            ProtectedWalletOperationsProxy proxy =
                    (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(ws2);
            assertTrue(proxy.supportsLoans());
            assertSame(proxy, ProtectedWalletOperationsProxy.asLoanServiceOrNull(proxy));

            User alice = auth2.authenticatePassword("09173000010", "Secret123").orElseThrow();
            SessionManager.getInstance().login(alice, ws2.getWallet(aliceWalletId));

            // Persisted state survives restart cleanly
            assertEquals(new BigDecimal("1000.00"), proxy.getBalance(aliceWalletId));
            assertEquals(new BigDecimal("1125.00"), proxy.getOutstandingAmountDue(aliceWalletId));
            List<LoanTransaction> aliceLoans = proxy.getLoansByWalletId(aliceWalletId);
            assertEquals(1, aliceLoans.size());
            assertEquals(LoanStatus.DISBURSED, aliceLoans.get(0).getLoanStatus());
            assertEquals("Approved Alice", aliceLoans.get(0).getDecisionReason());

            // Cross-account proxy isolation: Alice cannot inspect/apply/repay Bob's loans or call admin ops
            assertThrows(SecurityException.class, () -> proxy.getLoansByWalletId(bobWalletId));
            assertThrows(SecurityException.class, () -> proxy.getOutstandingAmountDue(bobWalletId));
            assertThrows(SecurityException.class, () -> proxy.previewLoanApplication(bobWalletId, new BigDecimal("1000.00")));
            assertThrows(SecurityException.class, () -> proxy.applyForLoan("REQ-HIJACK-LN", bobWalletId, new BigDecimal("1000.00")));
            assertThrows(SecurityException.class, () -> proxy.repayLoanFull("REQ-HIJACK-REP", bobWalletId, bobLoanId));
            assertThrows(SecurityException.class, () -> proxy.approveLoan(bobLoanId));
            assertThrows(SecurityException.class, () -> proxy.rejectLoan(alice.getUserId(), bobLoanId, "Unauthorized"));
            assertThrows(SecurityException.class, () -> proxy.getAllLoanApplications(alice.getUserId()));

            // Verify Bob's rejected loan also persisted
            User bob = auth2.authenticatePassword("09173000011", "Secret123").orElseThrow();
            SessionManager.getInstance().login(bob, ws2.getWallet(bobWalletId));
            List<LoanTransaction> bobLoans = proxy.getLoansByWalletId(bobWalletId);
            assertEquals(1, bobLoans.size());
            assertEquals(LoanStatus.REJECTED, bobLoans.get(0).getLoanStatus());
            assertEquals(new BigDecimal("0.00"), bobLoans.get(0).getAmountDue());
            assertEquals("Rejected Bob", bobLoans.get(0).getDecisionReason());
        }
    }

    @Test
    void testDashboardLoanPanelAsyncFlowRoleGatingAndStatementExport(@TempDir Path tempDir) throws Exception {
        User borrower = authService.registerUser("Nia Gomez", "09173000020", "nia.loan@otog.ph", "Secret123");
        Wallet borrowerWallet = walletService.getWalletByUserId(borrower.getUserId());
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        Wallet adminWallet = walletService.getWalletByUserId(admin.getUserId());

        SessionManager.getInstance().login(borrower, borrowerWallet);
        authService.setPin(borrower.getUserId(), "Secret123", "1234");

        ProtectedWalletOperationsProxy proxy =
                (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, proxy, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        assertTrue(activeDashboard.getVisibleTabTitles().contains("Loans"));
        assertTrue(activeDashboard.getLoansQuickNavButton().isVisible());
        SwingUtilities.invokeAndWait(() -> activeDashboard.getLoansQuickNavButton().doClick());
        assertEquals("Loans", activeDashboard.getTabs().getTitleAt(activeDashboard.getTabs().getSelectedIndex()));

        LoanPanel loanPanel = activeDashboard.getLoanPanel();
        assertNotNull(loanPanel);
        // Standard USER must NOT see Admin Loan Approvals sub-tab
        assertEquals(1, loanPanel.getTabs().getTabCount());

        // Async preview off EDT
        LoanService.LoanPreview preview = loanPanel.previewLoanAsync("1000.00").get(5, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("1125.00"), preview.getTotalRepaymentDue());
        assertTrue(loanPanel.getPreviewSummaryLabel().getText().contains("PHP 1,125.00"));

        // Async application off EDT
        LoanTransaction applied = loanPanel.submitLoanApplicationAsync("1000.00").get(5, TimeUnit.SECONDS);
        assertTrue(loanPanel.isLastOperationExecutedOffEdt());
        assertEquals(LoanStatus.PENDING_APPROVAL, applied.getLoanStatus());
        assertEquals(1, loanPanel.getUserLoansTable().getRowCount());
        assertEquals("PHP 0.00", loanPanel.getOutstandingDueLabel().getText());

        activeDashboard.dispose();
        activeDashboard = null;

        // Admin opens dashboard -> sees Admin Loan Approvals sub-tab (2 sub-tabs) and approves off EDT
        SessionManager.getInstance().login(admin, adminWallet);
        activeDashboard = new Main(proxy, authService, proxy, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        LoanPanel adminLoanPanel = activeDashboard.getLoanPanel();
        assertEquals(2, adminLoanPanel.getTabs().getTabCount());
        assertEquals(1, adminLoanPanel.getAdminLoansTable().getRowCount());

        LoanTransaction approved = adminLoanPanel
                .submitAdminApproveAsync(applied.getLoanId(), "Approved via UI").get(5, TimeUnit.SECONDS);
        assertTrue(adminLoanPanel.isLastOperationExecutedOffEdt());
        assertEquals(LoanStatus.DISBURSED, approved.getLoanStatus());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(borrowerWallet.getWalletId()));

        activeDashboard.dispose();
        activeDashboard = null;

        // Borrower logs back in -> sees PHP 1,125.00 due -> tops up -> repays in full off EDT
        walletService.cashIn("REQ-UI-LN-TOPUP", borrowerWallet.getWalletId(), null, new BigDecimal("200.00"), "Top up");
        SessionManager.getInstance().login(borrower, walletService.getWallet(borrowerWallet.getWalletId()));
        activeDashboard = new Main(proxy, authService, proxy, proxy);
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        LoanPanel repayPanel = activeDashboard.getLoanPanel();
        assertEquals("PHP 1,125.00", repayPanel.getOutstandingDueLabel().getText());
        assertEquals("PHP 1,200.00", activeDashboard.getBalanceLabel().getText());

        LoanTransaction repaid = repayPanel.submitRepayLoanAsync(applied.getLoanId(), "1234").get(5, TimeUnit.SECONDS);
        assertTrue(repayPanel.isLastOperationExecutedOffEdt());
        assertEquals(LoanStatus.PAID, repaid.getLoanStatus());
        assertEquals("PHP 0.00", repayPanel.getOutstandingDueLabel().getText());
        assertEquals("PHP 75.00", activeDashboard.getBalanceLabel().getText());

        // Repeat repayment rejected via UI async path too
        ExecutionException repeatEx = assertThrows(ExecutionException.class, () ->
                repayPanel.submitRepayLoanAsync(applied.getLoanId(), "1234").get(5, TimeUnit.SECONDS));
        assertInstanceOf(WalletException.class, repeatEx.getCause());
        assertTrue(repayPanel.getRepayFeedbackLabel().getText().contains("Repayment failed"));

        // Statement export/import includes both LOAN_DISBURSEMENT and LOAN_REPAYMENT cleanly
        Path stmtFile = tempDir.resolve("loan-statement.csv");
        activeDashboard.exportStatementAsync(stmtFile).get(5, TimeUnit.SECONDS);
        StatementFileService.LoadedStatement loaded = activeDashboard.importStatementAsync(stmtFile).get(5, TimeUnit.SECONDS);
        assertTrue(loaded.getEntries().stream().anyMatch(e -> e.getType() == TransactionType.LOAN_DISBURSEMENT));
        assertTrue(loaded.getEntries().stream().anyMatch(e -> e.getType() == TransactionType.LOAN_REPAYMENT));
    }

    @Test
    void testUserLoginOpenDashboardWithNonLoanWalletOperationsHidesLoansTabSafely() throws Exception {
        User user = authService.registerUser("Basic Ops Loan", "09173000021", "basicloan@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(user.getUserId());

        WalletOperations basicOps = new WalletOperations() {
            @Override public Wallet createWallet(String userId, BigDecimal initialBalance, String currency) { return walletService.createWallet(userId, initialBalance, currency); }
            @Override public Wallet getWallet(String walletId) { return walletService.getWallet(walletId); }
            @Override public Wallet getWalletByUserId(String userId) { return walletService.getWalletByUserId(userId); }
            @Override public BigDecimal getBalance(String walletId) { return walletService.getBalance(walletId); }
            @Override public Transaction transfer(String ref, String src, String tgt, BigDecimal amt, BigDecimal fee, String desc) { return walletService.transfer(ref, src, tgt, amt, fee, desc); }
            @Override public Transaction transferWithPin(String ref, String src, String tgt, BigDecimal amt, BigDecimal fee, String desc, String pin) { return walletService.transferWithPin(ref, src, tgt, amt, fee, desc, pin); }
            @Override public Transaction cashIn(String ref, String tgt, String pmId, BigDecimal amt, String desc) { return walletService.cashIn(ref, tgt, pmId, amt, desc); }
            @Override public Transaction cashOut(String ref, String src, String pmId, BigDecimal amt, BigDecimal fee, String desc) { return walletService.cashOut(ref, src, pmId, amt, fee, desc); }
            @Override public Transaction cashOutWithPin(String ref, String src, String pmId, BigDecimal amt, BigDecimal fee, String desc, String pin) { return walletService.cashOutWithPin(ref, src, pmId, amt, fee, desc, pin); }
        };

        assertFalse(((ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(basicOps)).supportsLoans());
        assertNull(ProtectedWalletOperationsProxy.asLoanServiceOrNull(ProtectedWalletOperationsProxy.wrap(basicOps)));

        Class<?> loginClass = Class.forName("UserLogin");
        Object loginWindow = loginClass.getConstructor(AuthService.class, WalletOperations.class)
                .newInstance(authService, basicOps);
        try {
            SessionManager.getInstance().login(user, wallet);
            Method openDashboard = loginClass.getMethod("openDashboard");
            AtomicReference<Main> dashRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    dashRef.set((Main) openDashboard.invoke(loginWindow));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            activeDashboard = dashRef.get();
            assertNotNull(activeDashboard);
            activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

            assertFalse(activeDashboard.getVisibleTabTitles().contains("Loans"));
            assertEquals(Main.DashboardState.EMPTY, activeDashboard.getCurrentState());
        } finally {
            if (loginWindow instanceof JFrame frame) {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }

    @Test
    void testInteractiveLoanApplicationPreservesPreviewRefAndSuspendedUsersAreBlocked() throws Exception {
        User borrower = authService.registerUser("Iris Tan", "09173000022", "iris.loan@otog.ph", "Secret123");
        Wallet borrowerWallet = walletService.getWalletByUserId(borrower.getUserId());
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();

        SessionManager.getInstance().login(borrower, borrowerWallet);
        authService.setPin(borrower.getUserId(), "Secret123", "1234");

        AtomicReference<String> previewRef = new AtomicReference<>();
        CountDownLatch submitDone = new CountDownLatch(1);
        ProtectedWalletOperationsProxy proxy =
                (ProtectedWalletOperationsProxy) ProtectedWalletOperationsProxy.wrap(walletService);
        activeDashboard = new Main(proxy, authService, proxy, proxy) {
            @Override
            protected int showConfirmDialog(java.awt.Component parent, String title, Object message, int optionType, int messageType) {
                if (message instanceof JScrollPane sp && sp.getViewport().getView() instanceof JTextArea area) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("Application Reference:\\s*(\\S+)")
                            .matcher(area.getText());
                    if (m.find()) {
                        previewRef.set(m.group(1));
                    }
                }
                return JOptionPane.OK_OPTION;
            }

            @Override
            protected void showDialogMessage(java.awt.Component parent, String title, String message, int messageType) {
                if ("Loan Application Submitted".equals(title)) {
                    submitDone.countDown();
                }
            }
        };
        activeDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);

        SwingUtilities.invokeAndWait(() -> {
            activeDashboard.navigateToTab("Loans");
            activeDashboard.getLoanPanel().getPrincipalField().setText("1,500.00");
            activeDashboard.getLoanPanel().getApplyLoanButton().doClick();
        });

        assertTrue(submitDone.await(5, TimeUnit.SECONDS), "Interactive loan submit should complete");
        assertNotNull(previewRef.get());
        List<LoanTransaction> loans = walletService.getLoansByWalletId(borrowerWallet.getWalletId());
        assertEquals(1, loans.size());
        assertEquals(previewRef.get(), loans.get(0).getReferenceNumber());

        // Suspending borrower blocks admin disbursement of pending loan
        SessionManager.getInstance().login(admin, walletService.getWalletByUserId(admin.getUserId()));
        walletService.suspendUser(admin.getUserId(), borrower.getUserId(), "Compliance hold");
        assertThrows(WalletException.class, () ->
                walletService.approveLoan(admin.getUserId(), loans.get(0).getLoanId(), "Should fail while suspended"));
        assertEquals(new BigDecimal("0.00"), walletService.getBalance(borrowerWallet.getWalletId()));
    }
}
