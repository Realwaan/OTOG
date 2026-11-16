package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.service.*;
import com.otog.util.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite verifying Ticket CAP-07:
 * Cash in/out operations, deterministic mock Bank API, funding adapters,
 * settlement lifecycles, timeout/pending states, fund reservations and releases,
 * idempotent repeated responses, and provider reference display.
 */
public class CashInOutMockBankApiTest {
    private DatabaseManager databaseManager;
    private WalletService walletService;
    private UserRepository userRepository;
    private WalletRepository walletRepository;
    private PaymentMethodRepository paymentMethodRepository;
    private TransactionRepository transactionRepository;
    private LedgerRepository ledgerRepository;

    private User testUser;
    private Wallet testWallet;
    private PaymentMethod testBankMethod;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);
        userRepository = walletService.getUserRepository();
        walletRepository = walletService.getWalletRepository();
        paymentMethodRepository = walletService.getPaymentMethodRepository();
        transactionRepository = walletService.getTransactionRepository();
        ledgerRepository = walletService.getLedgerRepository();

        String userId = IdGenerator.nextUserId();
        testUser = new User(userId, "Demo Juan", "09170001122", "juan@demo.ph", UserRole.USER, UserStatus.ACTIVE);
        userRepository.save(testUser);

        testWallet = walletService.createWallet(userId, new BigDecimal("1000.00"), "PHP");

        String pmId = IdGenerator.nextPaymentMethodId();
        testBankMethod = new PaymentMethod(pmId, userId, PaymentMethodType.BANK_ACCOUNT,
                "BDO Unibank", "Juan Dela Cruz", "•••• 1234");
        testBankMethod.setDefault(true);
        paymentMethodRepository.save(testBankMethod);
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    @Test
    @DisplayName("AC1: Default admin has seeded demo bank methods and users can link demo bank accounts")
    void testSeededDemoBankMethodsAndLinking() {
        // Verify default admin has seeded demo bank accounts
        List<PaymentMethod> adminMethods = paymentMethodRepository.findByUserId("USR-ADMIN-01");
        assertFalse(adminMethods.isEmpty(), "Default admin must have seeded demo bank methods");
        assertTrue(adminMethods.stream().anyMatch(pm -> "BDO Unibank".equals(pm.getProviderName())));
        assertTrue(adminMethods.stream().anyMatch(pm -> pm.getProviderName().contains("BPI")));
        assertTrue(adminMethods.stream().anyMatch(pm -> "Metrobank".equals(pm.getProviderName())));

        // Verify helper to seed demo bank methods for any user
        String newUserId = IdGenerator.nextUserId();
        userRepository.save(new User(newUserId, "Maria Demo", "09189998877"));
        List<PaymentMethod> linkedDemoMethods = walletService.seedDemoBankAccountsForUser(newUserId);
        assertEquals(3, linkedDemoMethods.size());

        Optional<PaymentMethod> defaultPm = walletService.getDefaultPaymentMethod(newUserId);
        assertTrue(defaultPm.isPresent());
        assertEquals("BDO Unibank", defaultPm.get().getProviderName());
    }

    @Test
    @DisplayName("AC1: PaymentProviderFactory supports Abstract Factory and Factory methods for deterministic modes")
    void testPaymentProviderFactoryModes() {
        // Abstract Factory usage
        PaymentProviderFactory successFactory = PaymentProviderFactory.successFactory();
        BankApiClient successClient = successFactory.createBankApiClient();
        BankApiResponse successResp = successClient.cashIn("REF-F-01", testBankMethod, new BigDecimal("100.00"));
        assertTrue(successResp.isSuccess());

        PaymentProviderFactory failureFactory = PaymentProviderFactory.failureFactory();
        BankApiClient failureClient = failureFactory.createBankApiClient();
        BankApiResponse failureResp = failureClient.cashIn("REF-F-02", testBankMethod, new BigDecimal("100.00"));
        assertTrue(failureResp.isFailure());

        PaymentProviderFactory timeoutFactory = PaymentProviderFactory.timeoutFactory();
        BankApiClient timeoutClient = timeoutFactory.createBankApiClient();
        BankApiResponse timeoutResp = timeoutClient.cashIn("REF-F-03", testBankMethod, new BigDecimal("100.00"));
        assertTrue(timeoutResp.isTimeout());

        PaymentProviderFactory pendingFactory = PaymentProviderFactory.pendingFactory();
        BankApiClient pendingClient = pendingFactory.createBankApiClient();
        BankApiResponse pendingResp = pendingClient.cashIn("REF-F-04", testBankMethod, new BigDecimal("100.00"));
        assertTrue(pendingResp.isPending());

        // Direct static factory methods
        BankApiClient directTimeoutClient = PaymentProviderFactory.createTimeoutClient();
        assertTrue(directTimeoutClient.cashOut("REF-F-05", testBankMethod, new BigDecimal("50.00")).isTimeout());
    }

    @Test
    @DisplayName("AC2: Cash-in credits wallet only on success; rejected cash-in does not move funds")
    void testCashInSuccessAndRejection() {
        BigDecimal initialBalance = walletService.getBalance(testWallet.getWalletId());
        assertEquals(new BigDecimal("1000.00"), initialBalance);

        // 1. Success cash-in
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        String successRef = "CIN-SUCCESS-01";
        Transaction txnSuccess = walletService.cashIn(successRef, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("500.00"), "Top-up via BDO");

        assertEquals(TransactionStatus.COMPLETED, txnSuccess.getStatus());
        assertNotNull(txnSuccess.getProviderReference());
        assertTrue(txnSuccess.getProviderReference().contains("BDO"));
        assertEquals("SUCCESS", txnSuccess.getProviderStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        // Receipt check
        String receipt = txnSuccess.getReceipt();
        assertTrue(receipt.contains("Provider Ref: " + txnSuccess.getProviderReference()));
        assertTrue(receipt.contains("Prov Status:  SUCCESS"));
        assertTrue(receipt.contains("Operation:    CASH_IN"));

        // 2. Rejected cash-in
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());
        String failRef = "CIN-FAIL-01";
        Transaction txnFail = walletService.cashIn(failRef, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("200.00"), "Failed top-up");

        assertEquals(TransactionStatus.FAILED, txnFail.getStatus());
        assertEquals("REJECTED", txnFail.getProviderStatus());
        assertNotNull(txnFail.getProviderReference());
        // Balance remains unchanged
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC2: Cash-out reserves funds immediately until settlement, releasing them on confirmed failure")
    void testCashOutSuccessAndImmediateRejection() {
        // Initial balance 1000.00
        BigDecimal initialBalance = walletService.getBalance(testWallet.getWalletId());
        assertEquals(new BigDecimal("1000.00"), initialBalance);

        // 1. Successful cash-out
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        String successRef = "COUT-SUCCESS-01";
        Transaction txnSuccess = walletService.cashOut(successRef, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("300.00"), new BigDecimal("15.00"), "Withdraw to BDO");

        assertEquals(TransactionStatus.COMPLETED, txnSuccess.getStatus());
        assertEquals(new BigDecimal("685.00"), walletService.getBalance(testWallet.getWalletId()));
        assertNotNull(txnSuccess.getProviderReference());

        // 2. Immediately rejected cash-out -> funds reserved then released on confirmed failure
        walletService.setPaymentProviderFactory(PaymentProviderFactory.failureFactory());
        String failRef = "COUT-FAIL-01";
        Transaction txnFail = walletService.cashOut(failRef, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("200.00"), new BigDecimal("10.00"), "Declined withdrawal");

        assertEquals(TransactionStatus.FAILED, txnFail.getStatus());
        assertEquals("REJECTED", txnFail.getProviderStatus());
        // Funds released back to 685.00
        assertEquals(new BigDecimal("685.00"), walletService.getBalance(testWallet.getWalletId()));

        // Verify ledger recorded reservation and refund
        List<LedgerEntry> userEntries = ledgerRepository.findByWalletId(testWallet.getWalletId());
        boolean hasRefund = userEntries.stream().anyMatch(e -> e.getDescription().contains("released reserved funds"));
        assertTrue(hasRefund, "Ledger must show release of reserved funds on confirmed failure");
    }

    @Test
    @DisplayName("AC3: Cash-in timeout stays pending, crediting wallet only after successful status lookup")
    void testCashInTimeoutFollowedByStatusLookup() {
        MockBankAdapter mockAdapter = new MockBankAdapter(BankResponseStatus.TIMEOUT);
        walletService.setBankApiClient(new BankApiClient(mockAdapter));

        String timeoutRef = "CIN-TIMEOUT-01";
        Transaction pendingTxn = walletService.cashIn(timeoutRef, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("400.00"), "Pending Cash In");

        assertEquals(TransactionStatus.PENDING, pendingTxn.getStatus());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));

        // Status lookup while still TIMEOUT -> stays pending
        Transaction stillPending = walletService.statusLookup(timeoutRef);
        assertEquals(TransactionStatus.PENDING, stillPending.getStatus());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));

        // Bank completes transaction -> status lookup returns SUCCESS
        mockAdapter.setDefaultLookupStatus(BankResponseStatus.SUCCESS);
        Transaction completedTxn = walletService.statusLookup(timeoutRef);

        assertEquals(TransactionStatus.COMPLETED, completedTxn.getStatus());
        assertEquals("SUCCESS", completedTxn.getProviderStatus());
        // Now credited
        assertEquals(new BigDecimal("1400.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC3: Cash-in timeout followed by rejection leaves wallet uncredited")
    void testCashInTimeoutFollowedByRejection() {
        MockBankAdapter mockAdapter = new MockBankAdapter(BankResponseStatus.TIMEOUT);
        walletService.setBankApiClient(new BankApiClient(mockAdapter));

        String ref = "CIN-TIMEOUT-FAIL";
        Transaction pendingTxn = walletService.cashIn(ref, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("300.00"), "Pending Cash In");

        assertEquals(TransactionStatus.PENDING, pendingTxn.getStatus());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));

        // Bank rejects transaction -> status lookup returns REJECTED
        mockAdapter.setDefaultLookupStatus(BankResponseStatus.REJECTED);
        Transaction failedTxn = walletService.statusLookup(ref);

        assertEquals(TransactionStatus.FAILED, failedTxn.getStatus());
        assertEquals("REJECTED", failedTxn.getProviderStatus());
        // Balance remains untouched
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC3: Cash-out timeout reserves funds, settling on success or releasing funds on rejection lookup")
    void testCashOutTimeoutFollowedByStatusLookup() {
        MockBankAdapter mockAdapter = new MockBankAdapter(BankResponseStatus.TIMEOUT);
        walletService.setBankApiClient(new BankApiClient(mockAdapter));

        // Cash out 200.00 with 10.00 fee -> total debit 210.00
        String ref = "COUT-TIMEOUT-01";
        Transaction pendingTxn = walletService.cashOut(ref, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("200.00"), new BigDecimal("10.00"), "Pending Cash Out");

        assertEquals(TransactionStatus.PENDING, pendingTxn.getStatus());
        // Funds are RESERVED: balance reduced from 1000.00 to 790.00
        assertEquals(new BigDecimal("790.00"), walletService.getBalance(testWallet.getWalletId()));

        // Status lookup while still TIMEOUT -> stays pending, funds remain reserved
        Transaction stillPending = walletService.statusLookup(ref);
        assertEquals(TransactionStatus.PENDING, stillPending.getStatus());
        assertEquals(new BigDecimal("790.00"), walletService.getBalance(testWallet.getWalletId()));

        // Scenario A: Bank confirms rejection -> reserved funds released
        mockAdapter.setDefaultLookupStatus(BankResponseStatus.REJECTED);
        Transaction rejectedTxn = walletService.statusLookup(ref);

        assertEquals(TransactionStatus.FAILED, rejectedTxn.getStatus());
        assertEquals("REJECTED", rejectedTxn.getProviderStatus());
        // Funds released back to 1000.00
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));

        // Scenario B: Test another cash-out with timeout followed by SUCCESS settlement
        mockAdapter.setMode(BankResponseStatus.TIMEOUT);
        mockAdapter.setDefaultLookupStatus(null);
        String refSuccess = "COUT-TIMEOUT-SETTLE";
        Transaction timeoutTxn2 = walletService.cashOut(refSuccess, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("100.00"), BigDecimal.ZERO, "Settle test");

        assertEquals(TransactionStatus.PENDING, timeoutTxn2.getStatus());
        assertEquals(new BigDecimal("900.00"), walletService.getBalance(testWallet.getWalletId()));

        mockAdapter.setDefaultLookupStatus(BankResponseStatus.SUCCESS);
        Transaction settledTxn = walletService.statusLookup(refSuccess);
        assertEquals(TransactionStatus.COMPLETED, settledTxn.getStatus());
        assertEquals("SUCCESS", settledTxn.getProviderStatus());
        // Funds stay settled at 900.00
        assertEquals(new BigDecimal("900.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC4: Repeated provider responses never duplicate money movement")
    void testRepeatedProviderResponsesNeverDuplicateMoneyMovement() {
        MockBankAdapter mockAdapter = new MockBankAdapter(BankResponseStatus.TIMEOUT);
        walletService.setBankApiClient(new BankApiClient(mockAdapter));

        // 1. Cash In repeated responses
        String inRef = "CIN-IDEMPOTENT-01";
        walletService.cashIn(inRef, testWallet.getWalletId(), testBankMethod.getPaymentMethodId(),
                new BigDecimal("500.00"), "Idempotent in");
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(testWallet.getWalletId()));

        mockAdapter.setDefaultLookupStatus(BankResponseStatus.SUCCESS);
        Transaction settledIn = walletService.statusLookup(inRef);
        assertEquals(TransactionStatus.COMPLETED, settledIn.getStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        // Repeated lookups or callbacks MUST NOT credit again
        Transaction duplicateLookup1 = walletService.statusLookup(inRef);
        assertEquals(TransactionStatus.COMPLETED, duplicateLookup1.getStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        walletService.handleProviderResponse(inRef, BankApiResponse.success(settledIn.getProviderReference(), "Duplicate webhook"));
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        // 2. Cash Out repeated responses
        mockAdapter.setMode(BankResponseStatus.TIMEOUT);
        mockAdapter.setDefaultLookupStatus(null);
        String outRef = "COUT-IDEMPOTENT-01";
        walletService.cashOut(outRef, testWallet.getWalletId(), testBankMethod.getPaymentMethodId(),
                new BigDecimal("300.00"), BigDecimal.ZERO, "Idempotent out");
        assertEquals(new BigDecimal("1200.00"), walletService.getBalance(testWallet.getWalletId()));

        // Confirmed failure releases 300 back to 1500
        mockAdapter.setDefaultLookupStatus(BankResponseStatus.REJECTED);
        Transaction failedOut = walletService.statusLookup(outRef);
        assertEquals(TransactionStatus.FAILED, failedOut.getStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        // Repeated status lookups MUST NOT release funds again
        Transaction duplicateOutLookup = walletService.statusLookup(outRef);
        assertEquals(TransactionStatus.FAILED, duplicateOutLookup.getStatus());
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));

        walletService.handleProviderResponse(outRef, BankApiResponse.rejected(failedOut.getProviderReference(), "Duplicate reject"));
        assertEquals(new BigDecimal("1500.00"), walletService.getBalance(testWallet.getWalletId()));
    }

    @Test
    @DisplayName("AC4: CashInOutTransaction displays provider reference and status in receipt and model")
    void testDisplayProviderReferenceAndStatus() {
        walletService.setPaymentProviderFactory(PaymentProviderFactory.successFactory());
        String ref = "CIN-DISPLAY-01";
        Transaction txn = walletService.cashIn(ref, testWallet.getWalletId(),
                testBankMethod.getPaymentMethodId(), new BigDecimal("150.00"), "Display test");

        assertInstanceOf(CashInOutTransaction.class, txn);
        CashInOutTransaction cio = (CashInOutTransaction) txn;

        assertNotNull(cio.getProviderReference());
        assertEquals("SUCCESS", cio.getProviderStatus());
        assertEquals("BDO Unibank", cio.getChannel());
        assertEquals("CASH_IN", cio.getCashOperationType());

        String receipt = cio.getReceipt();
        assertTrue(receipt.contains("Provider Ref: " + cio.getProviderReference()));
        assertTrue(receipt.contains("Prov Status:  SUCCESS"));
        assertTrue(receipt.contains("Operation:    CASH_IN"));
        assertTrue(receipt.contains("Channel:      BDO Unibank"));
        assertTrue(receipt.contains("Status:       COMPLETED"));
    }
}
