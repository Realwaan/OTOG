package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.WalletException;
import com.otog.model.*;
import com.otog.repository.*;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests AC 1: Persist User, Wallet, Transaction, PaymentMethod, and ledger entries
 * with ownership and unique references.
 */
public class UserWalletPersistenceTest {
    private DatabaseManager databaseManager;
    private WalletService walletService;
    private UserRepository userRepository;
    private WalletRepository walletRepository;
    private PaymentMethodRepository paymentMethodRepository;
    private TransactionRepository transactionRepository;
    private LedgerRepository ledgerRepository;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        walletService = new WalletService(databaseManager);
        userRepository = walletService.getUserRepository();
        walletRepository = walletService.getWalletRepository();
        paymentMethodRepository = walletService.getPaymentMethodRepository();
        transactionRepository = walletService.getTransactionRepository();
        ledgerRepository = walletService.getLedgerRepository();
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    @Test
    void testPersistUserAndWalletWithOwnership() {
        String userId = IdGenerator.nextUserId();
        User user = new User(userId, "Juan Dela Cruz", "09171234567", "juan@otog.ph", UserRole.USER, UserStatus.ACTIVE);
        userRepository.save(user);

        Optional<User> foundUser = userRepository.findById(userId);
        assertTrue(foundUser.isPresent(), "User should be persisted and found by ID");
        assertEquals("Juan Dela Cruz", foundUser.get().getName());
        assertEquals("09171234567", foundUser.get().getMobileNumber());
        assertEquals(UserRole.USER, foundUser.get().getRole());
        assertEquals(UserStatus.ACTIVE, foundUser.get().getStatus());

        // Create Wallet owned by this user
        Wallet wallet = walletService.createWallet(userId, new BigDecimal("500.00"), "PHP");
        assertNotNull(wallet.getWalletId());
        assertEquals(userId, wallet.getUserId(), "Wallet must reference its owner user ID");
        assertEquals(new BigDecimal("500.00"), wallet.getBalance());
        assertEquals("PHP", wallet.getCurrency());
        assertEquals(WalletStatus.ACTIVE, wallet.getStatus());

        // Lookup wallet by ID and by User ID
        Optional<Wallet> walletById = walletRepository.findById(wallet.getWalletId());
        assertTrue(walletById.isPresent());
        assertEquals(wallet.getWalletId(), walletById.get().getWalletId());

        Optional<Wallet> walletByUser = walletRepository.findByUserId(userId);
        assertTrue(walletByUser.isPresent());
        assertEquals(wallet.getWalletId(), walletByUser.get().getWalletId());

        // Verify initial deposit generated a transaction and ledger entry
        List<Transaction> userTxns = transactionRepository.findByWalletId(wallet.getWalletId());
        assertEquals(1, userTxns.size());
        assertEquals(TransactionType.CASH_IN, userTxns.get(0).getType());
        assertEquals(TransactionStatus.COMPLETED, userTxns.get(0).getStatus());

        List<LedgerEntry> ledgerEntries = ledgerRepository.findByWalletId(wallet.getWalletId());
        assertEquals(1, ledgerEntries.size());
        assertEquals(LedgerEntryType.CREDIT, ledgerEntries.get(0).getEntryType());
        assertEquals(new BigDecimal("500.00"), ledgerEntries.get(0).getAmount());
        assertEquals(new BigDecimal("0.00"), ledgerEntries.get(0).getBalanceBefore());
        assertEquals(new BigDecimal("500.00"), ledgerEntries.get(0).getBalanceAfter());
    }

    @Test
    void testUniqueMobileNumberConstraint() {
        String u1 = IdGenerator.nextUserId();
        String u2 = IdGenerator.nextUserId();
        userRepository.save(new User(u1, "User One", "09181112233"));

        // Attempt to insert duplicate mobile number
        User duplicate = new User(u2, "User Two", "09181112233");
        assertThrows(RuntimeException.class, () -> userRepository.save(duplicate),
                "Database should enforce UNIQUE constraint on mobile_number");
    }

    @Test
    void testOneWalletPerUserRule() {
        String userId = IdGenerator.nextUserId();
        userRepository.save(new User(userId, "Maria Santos", "09209876543"));
        walletService.createWallet(userId, BigDecimal.ZERO, "PHP");

        // Attempting to create a second wallet for the same user must be rejected
        assertThrows(WalletException.class, () ->
                walletService.createWallet(userId, new BigDecimal("100.00"), "PHP"));
    }

    @Test
    void testPersistPaymentMethod() {
        String userId = IdGenerator.nextUserId();
        userRepository.save(new User(userId, "Pedro Penduko", "09195556677"));

        String pmId = IdGenerator.nextPaymentMethodId();
        PaymentMethod pm = new PaymentMethod(pmId, userId, PaymentMethodType.BANK_ACCOUNT,
                "BDO Unibank", "Pedro Penduko", "•••• 1234");
        pm.setDefault(true);
        paymentMethodRepository.save(pm);

        Optional<PaymentMethod> foundPm = paymentMethodRepository.findById(pmId);
        assertTrue(foundPm.isPresent());
        assertEquals("BDO Unibank", foundPm.get().getProviderName());
        assertTrue(foundPm.get().isDefault());
        assertEquals(PaymentMethodStatus.ACTIVE, foundPm.get().getStatus());

        List<PaymentMethod> userPms = paymentMethodRepository.findByUserId(userId);
        assertEquals(1, userPms.size());
        assertEquals(pmId, userPms.get(0).getPaymentMethodId());

        Optional<PaymentMethod> defaultPm = paymentMethodRepository.findDefaultByUserId(userId);
        assertTrue(defaultPm.isPresent());
        assertEquals(pmId, defaultPm.get().getPaymentMethodId());
    }

    @Test
    void testSpecializedTransactionsAndReceipts() {
        String u1 = IdGenerator.nextUserId();
        String u2 = IdGenerator.nextUserId();
        userRepository.save(new User(u1, "Sender", "09110000001"));
        userRepository.save(new User(u2, "Recipient", "09110000002"));

        Wallet w1 = walletService.createWallet(u1, new BigDecimal("1000.00"), "PHP");
        Wallet w2 = walletService.createWallet(u2, new BigDecimal("200.00"), "PHP");

        // SendMoney transaction
        String ref = IdGenerator.nextReferenceNumber();
        Transaction txn = walletService.transfer(ref, w1.getWalletId(), w2.getWalletId(),
                new BigDecimal("250.00"), new BigDecimal("15.00"), "Dinner split");

        assertNotNull(txn.getTransactionId());
        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
        assertEquals(new BigDecimal("250.00"), txn.getAmount());
        assertEquals(new BigDecimal("15.00"), txn.getFee());
        assertEquals(new BigDecimal("265.00"), txn.getTotalAmount());

        // Verify receipt string contains required details
        String receipt = txn.getReceipt();
        assertNotNull(receipt);
        assertTrue(receipt.contains("OTOG E-WALLET RECEIPT"));
        assertTrue(receipt.contains(txn.getTransactionId()));
        assertTrue(receipt.contains(ref));
        assertTrue(receipt.contains("PHP 250.00"));
        assertTrue(receipt.contains("PHP 15.00"));
        assertTrue(receipt.contains("PHP 265.00"));
        assertTrue(receipt.contains("Dinner split"));

        // Test SendMoneyTransaction model
        SendMoneyTransaction smt = new SendMoneyTransaction(IdGenerator.nextTransactionId(),
                "REQ-SM-1", w1.getWalletId(), w2.getWalletId(),
                new BigDecimal("100.00"), BigDecimal.ZERO, "09110000002", "Gift");
        assertTrue(smt.validateRecipient());
        assertEquals("09110000002", smt.getRecipientNumber());

        // Test CashInOutTransaction model
        CashInOutTransaction cio = new CashInOutTransaction(IdGenerator.nextTransactionId(),
                "REQ-CIO-1", w1.getWalletId(), new BigDecimal("300.00"), BigDecimal.ZERO, "CASH_IN", "7-Eleven");
        assertEquals("CASH_IN", cio.getCashOperationType());
        assertEquals("7-Eleven", cio.getChannel());

        // Test InvestMoneyTransaction model
        InvestMoneyTransaction imt = new InvestMoneyTransaction(IdGenerator.nextTransactionId(),
                "REQ-INV-1", w1.getWalletId(), new BigDecimal("1000.00"), BigDecimal.ZERO, "OTOG Balanced Fund", new BigDecimal("10.0"));
        assertEquals("OTOG Balanced Fund", imt.getProduct());
        assertEquals(new BigDecimal("50.00"), imt.getReturns());

        // Test LoanTransaction model
        LoanTransaction lt = new LoanTransaction(IdGenerator.nextTransactionId(),
                "REQ-LN-1", w1.getWalletId(), new BigDecimal("5000.00"), new BigDecimal("0.03"), 6, LocalDate.now().plusMonths(6));
        assertEquals(new BigDecimal("5000.00"), lt.getPrincipal());
        assertEquals(6, lt.getTermMonths());
    }
}
