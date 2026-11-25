package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.MalformedQrException;
import com.otog.exception.WalletException;
import com.otog.model.MerchantQrPayload;
import com.otog.model.Notification;
import com.otog.model.NotificationType;
import com.otog.model.ReceiptBuilder;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.model.WalletStatus;
import com.otog.repository.UserRepository;
import com.otog.repository.WalletRepository;
import com.otog.service.AuthServiceImpl;
import com.otog.service.MerchantQrService;
import com.otog.service.MerchantQrService.MerchantPaymentPreview;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.Main;
import com.otog.ui.MerchantQrPanel;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for CAP-11 Merchant QR Payments:
 * 1. Default merchant profile seeding & verification
 * 2. Visual QR image generation, PNG raster lossless encoding & decoding round trip
 * 3. Pre-payment confirmation preview with merchant validation & masked identifiers
 * 4. Atomic transfer payment execution with PIN authorization & balance updates
 * 5. Merchant received payments view & receipt formatting (customer vs. merchant perspective)
 * 6. All rejection cases:
 *    - Malformed QR (non-image, missing header, raster checksum corruption, payload tampering)
 *    - Inactive / suspended merchant account
 *    - Inactive / frozen merchant wallet
 *    - Non-merchant account in QR
 *    - Non-PHP currency
 *    - Invalid / negative / excessive scale amount
 *    - Insufficient customer balance
 *    - Self-payment attempt to own merchant account
 *    - Duplicate submission / idempotency rejection
 *    - Invalid / incorrect transaction PIN
 */
public class MerchantQrPaymentTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;
    private UserRepository userRepository;
    private WalletRepository walletRepository;
    private MerchantQrService merchantQrService;

    private User seededMerchant;
    private Wallet seededMerchantWallet;
    private User customer;
    private Wallet customerWallet;

    @BeforeEach
    void setUp() {
        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = new WalletService(databaseManager, authService);
        userRepository = new UserRepository(databaseManager);
        walletRepository = new WalletRepository(databaseManager);
        merchantQrService = new MerchantQrService(walletService, userRepository, authService);
        SessionManager.getInstance().reset();

        // Retrieve seeded merchant
        OtogConfig.MerchantSeedConfig seedConfig = OtogConfig.getInstance().getMerchantSeedConfig();
        seededMerchant = userRepository.findById(seedConfig.userId())
                .orElseThrow(() -> new IllegalStateException("Seeded merchant not found: " + seedConfig.userId()));
        seededMerchantWallet = walletService.getWallet(seedConfig.walletId());
        assertNotNull(seededMerchantWallet, "Seeded merchant wallet must exist");

        // Register a test customer with 5,000 PHP balance and PIN 1234
        customer = authService.registerUser("Juan Customer", "09171112233", "juan.customer@otog.ph", "CustomerPass123");
        customerWallet = walletService.getWalletByUserId(customer.getUserId());
        walletService.cashIn("REQ-SEED-CUST", customerWallet.getWalletId(), null, new BigDecimal("5000.00"), "Initial Balance");
        customerWallet = walletService.getWallet(customerWallet.getWalletId());
        assertEquals(0, new BigDecimal("5000.00").compareTo(customerWallet.getBalance()));
        SessionManager.getInstance().login(customer, customerWallet);
        authService.setPin(customer.getUserId(), "CustomerPass123", "1234");
    }

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    // --- 1. Seeded Merchant Profile Tests ---

    @Test
    void testSeededMerchantProfileAndWalletExistAndAreActive() {
        OtogConfig.MerchantSeedConfig seedConfig = OtogConfig.getInstance().getMerchantSeedConfig();

        Optional<User> merchantOpt = userRepository.findById(seedConfig.userId());
        assertTrue(merchantOpt.isPresent(), "Seeded merchant account must exist in database");

        User merchant = merchantOpt.get();
        assertEquals(seedConfig.username(), merchant.getName());
        assertEquals(seedConfig.mobile(), merchant.getMobileNumber());
        assertEquals(UserRole.MERCHANT, merchant.getRole());
        assertEquals(UserStatus.ACTIVE, merchant.getStatus());

        Wallet wallet = walletService.getWallet(seedConfig.walletId());
        assertNotNull(wallet, "Seeded merchant wallet must exist");
        assertEquals(merchant.getUserId(), wallet.getUserId());
        assertEquals(WalletStatus.ACTIVE, wallet.getStatus());
        assertEquals("PHP", wallet.getCurrency());
    }

    // --- 2. QR Code Generation, File Export & Import Round Trip ---

    @Test
    void testQrCodeGenerationAndLosslessImageRoundTrip(@TempDir Path tempDir) {
        MerchantQrPayload originalPayload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("175.50"),
                "PHP",
                "REF-ORDER-001",
                "Table 12 Lunch"
        );

        Path qrFile = tempDir.resolve("test_merchant_qr.png");
        Path exported = merchantQrService.exportQrImage(originalPayload, qrFile);
        assertTrue(Files.isRegularFile(exported), "Exported QR image file must exist");

        // Import and verify decoded payload
        MerchantQrPayload imported = merchantQrService.importQrImage(exported);
        assertNotNull(imported);
        assertEquals(originalPayload.getMerchantId(), imported.getMerchantId());
        assertEquals(originalPayload.getMerchantWalletId(), imported.getMerchantWalletId());
        assertEquals(originalPayload.getMerchantName(), imported.getMerchantName());
        assertTrue(imported.hasFixedAmount());
        assertEquals(0, originalPayload.getAmount().compareTo(imported.getAmount()));
        assertEquals(originalPayload.getCurrency(), imported.getCurrency());
        assertEquals(originalPayload.getQrReference(), imported.getQrReference());
        assertEquals(originalPayload.getNote(), imported.getNote());
        assertEquals(originalPayload.getChecksum(), imported.getChecksum());
    }

    @Test
    void testOpenAmountQrCodeGenerationAndRoundTrip(@TempDir Path tempDir) {
        MerchantQrPayload openPayload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                null, // open amount
                "PHP",
                "REF-OPEN-002",
                "Store Donation"
        );

        Path qrFile = tempDir.resolve("open_qr.png");
        merchantQrService.exportQrImage(openPayload, qrFile);

        MerchantQrPayload imported = merchantQrService.importQrImage(qrFile);
        assertFalse(imported.hasFixedAmount());
        assertNull(imported.getAmount());
        assertEquals("REF-OPEN-002", imported.getQrReference());
        assertEquals("Store Donation", imported.getNote());
    }

    // --- 3. Pre-Payment Confirmation Preview ---

    @Test
    void testPreviewPaymentWithFixedAmount() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("350.00"),
                "PHP",
                "REF-PREVIEW-01",
                "Combo Meal"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, null);

        assertNotNull(preview);
        assertEquals(customer.getUserId(), preview.getCustomerUserId());
        assertEquals(customerWallet.getWalletId(), preview.getCustomerWalletId());
        assertEquals(seededMerchant.getUserId(), preview.getMerchant().getUserId());
        assertEquals(seededMerchantWallet.getWalletId(), preview.getMerchantWalletId());
        assertEquals(0, new BigDecimal("350.00").compareTo(preview.getAmount()));
        assertEquals("PHP", preview.getCurrency());
        assertEquals("REF-PREVIEW-01", preview.getQrReference());

        String confirmText = preview.toConfirmationText();
        assertTrue(confirmText.contains(seededMerchant.getName()));
        assertTrue(confirmText.contains("350.00"));
        assertTrue(confirmText.contains("REF-PREVIEW-01"));
        assertTrue(confirmText.contains(ReceiptBuilder.maskIdentifier(seededMerchant.getMobileNumber())));
    }

    @Test
    void testPreviewPaymentWithOpenAmount() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                null,
                "PHP",
                "REF-PREVIEW-02",
                "Tip"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, new BigDecimal("50.00"));

        assertEquals(0, new BigDecimal("50.00").compareTo(preview.getAmount()));
    }

    // --- 4. Atomic Payment Execution & Double-Entry Ledger Verification ---

    @Test
    void testSuccessfulPaymentExecutionWithPin() {
        BigDecimal paymentAmount = new BigDecimal("450.00");
        BigDecimal initialCustomerBalance = customerWallet.getBalance();
        BigDecimal initialMerchantBalance = seededMerchantWallet.getBalance();

        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                paymentAmount,
                "PHP",
                "REF-PAY-SUCCESS-01",
                "Grocery items"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, null);

        Transaction txn = merchantQrService.confirmAndPay(preview, "1234");

        assertNotNull(txn);
        assertEquals(TransactionStatus.COMPLETED, txn.getStatus());
        assertEquals(customerWallet.getWalletId(), txn.getSourceWalletId());
        assertEquals(seededMerchantWallet.getWalletId(), txn.getTargetWalletId());
        assertEquals(0, paymentAmount.compareTo(txn.getAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(txn.getFee()));
        assertEquals(0, paymentAmount.compareTo(txn.getTotalAmount()));

        // Verify customer wallet debited exactly
        Wallet updatedCust = walletService.getWallet(customerWallet.getWalletId());
        assertEquals(0, initialCustomerBalance.subtract(paymentAmount).compareTo(updatedCust.getBalance()));

        // Verify merchant wallet credited exactly
        Wallet updatedMerchant = walletService.getWallet(seededMerchantWallet.getWalletId());
        assertEquals(0, initialMerchantBalance.add(paymentAmount).compareTo(updatedMerchant.getBalance()));
    }

    // --- 5. Merchant Received Payments & Receipt View Formatting ---

    @Test
    void testMerchantReceivedPaymentsAndReceiptView() {
        BigDecimal amount = new BigDecimal("299.00");
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                amount,
                "PHP",
                "REF-RECEIPT-01",
                "Dinner special"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, null);
        Transaction txn = merchantQrService.confirmAndPay(preview, "1234");

        // Merchant checks received transactions
        List<Transaction> merchantTxns = walletService.getRecentTransactions(seededMerchantWallet.getWalletId(), 10);
        assertTrue(merchantTxns.stream().anyMatch(t -> t.getTransactionId().equals(txn.getTransactionId())),
                "Merchant must see the received transaction in history");

        // Format Merchant-perspective Receipt
        String merchantReceipt = ReceiptBuilder.forMerchantPayment(
                txn, seededMerchant.getName(), payload.getQrReference(), true).build();
        assertTrue(merchantReceipt.contains("OTOG MERCHANT PAYMENT RECEIPT"));
        assertTrue(merchantReceipt.contains("Customer"));
        assertTrue(merchantReceipt.contains(ReceiptBuilder.maskIdentifier(customerWallet.getWalletId())));
        assertTrue(merchantReceipt.contains("Net Credited"));
        assertTrue(merchantReceipt.contains("299.00"));

        // Format Customer-perspective Receipt
        String customerReceipt = ReceiptBuilder.forMerchantPayment(
                txn, seededMerchant.getName(), payload.getQrReference(), false).build();
        assertTrue(customerReceipt.contains("OTOG MERCHANT PURCHASE RECEIPT"));
        assertTrue(customerReceipt.contains("Source"));
        assertTrue(customerReceipt.contains(ReceiptBuilder.maskIdentifier(customerWallet.getWalletId())));
        assertTrue(customerReceipt.contains("Total Paid"));
        assertTrue(customerReceipt.contains("299.00"));
    }

    // --- 6. Rejection Cases ---

    @Test
    void testRejectNonImageFile(@TempDir Path tempDir) throws IOException {
        Path textFile = tempDir.resolve("fake.png");
        Files.writeString(textFile, "This is not an image file at all.");

        assertThrows(MalformedQrException.class, () -> merchantQrService.importQrImage(textFile));
    }

    @Test
    void testRejectMissingMarkerImage(@TempDir Path tempDir) throws IOException {
        BufferedImage blank = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Path blankFile = tempDir.resolve("blank.png");
        ImageIO.write(blank, "PNG", blankFile.toFile());

        assertThrows(MalformedQrException.class, () -> merchantQrService.importQrImage(blankFile));
    }

    @Test
    void testRejectTamperedRasterChecksum(@TempDir Path tempDir) throws IOException {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("100.00"),
                "PHP",
                "REF-TAMPER-01",
                "Test"
        );
        Path qrFile = tempDir.resolve("tampered.png");
        merchantQrService.exportQrImage(payload, qrFile);

        // Read image and tamper with one encoded pixel at the bottom
        BufferedImage img = ImageIO.read(qrFile.toFile());
        img.setRGB(15, img.getHeight() - 1, 0xFF00FF00); // mutate a payload byte
        ImageIO.write(img, "PNG", qrFile.toFile());

        assertThrows(MalformedQrException.class, () -> merchantQrService.importQrImage(qrFile));
    }

    @Test
    void testRejectTamperedPayloadString() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("100.00"),
                "PHP",
                "REF-STRING-01",
                "Test"
        );
        String encoded = payload.encode();
        // Tamper with amount in encoded string
        String tampered = encoded.replace("amt=100.00", "amt=999.00");

        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(tampered));
    }

    @Test
    void testRejectInactiveMerchantAccount() {
        // Suspend merchant
        databaseManager.executeInTransaction(conn -> {
            userRepository.updateStatus(seededMerchant.getUserId(), UserStatus.SUSPENDED, conn);
            return null;
        });

        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("50.00"),
                "PHP",
                "REF-SUSPEND-01",
                "Test"
        );

        WalletException ex = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), payload, null));
        assertTrue(ex.getMessage().contains("inactive") || ex.getMessage().contains("SUSPENDED"));
    }

    @Test
    void testRejectFrozenMerchantWallet() {
        // Freeze merchant wallet
        databaseManager.executeInTransaction(conn -> {
            seededMerchantWallet.setStatus(WalletStatus.FROZEN);
            walletRepository.update(seededMerchantWallet, conn);
            return null;
        });

        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("50.00"),
                "PHP",
                "REF-FREEZE-01",
                "Test"
        );

        WalletException ex = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), payload, null));
        assertTrue(ex.getMessage().contains("not active") || ex.getMessage().contains("FROZEN"));
    }

    @Test
    void testRejectNonMerchantAccount() {
        // Create standard customer
        User regularUser = authService.registerUser("Regular User", "09172223344", "regular@otog.ph", "Pass123");
        Wallet regularWallet = walletService.getWalletByUserId(regularUser.getUserId());

        MerchantQrPayload payload = new MerchantQrPayload(
                regularUser.getUserId(),
                regularWallet.getWalletId(),
                regularUser.getName(),
                new BigDecimal("50.00"),
                "PHP",
                "REF-NON-MERCHANT",
                "Test"
        );

        WalletException ex = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), payload, null));
        assertTrue(ex.getMessage().contains("not a registered merchant"));
    }

    @Test
    void testRejectNonPhpCurrency() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("50.00"),
                "USD", // foreign currency
                "REF-USD-01",
                "Test"
        );

        WalletException ex = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), payload, null));
        assertTrue(ex.getMessage().contains("Unsupported currency"));
    }

    @Test
    void testRejectInvalidOrNegativeOrExcessiveScaleAmount() {
        // Negative amount during payload creation
        assertThrows(InvalidAmountException.class, () ->
                new MerchantQrPayload(
                        seededMerchant.getUserId(),
                        seededMerchantWallet.getWalletId(),
                        seededMerchant.getName(),
                        new BigDecimal("-20.00"),
                        "PHP",
                        "REF-NEG-01",
                        "Test"
                ));

        // Scale > 2
        assertThrows(InvalidAmountException.class, () ->
                new MerchantQrPayload(
                        seededMerchant.getUserId(),
                        seededMerchantWallet.getWalletId(),
                        seededMerchant.getName(),
                        new BigDecimal("20.123"),
                        "PHP",
                        "REF-SCALE-01",
                        "Test"
                ));

        // Open QR with negative input amount in previewPayment
        MerchantQrPayload openPayload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                null,
                "PHP",
                "REF-OPEN-03",
                "Test"
        );
        assertThrows(InvalidAmountException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), openPayload, new BigDecimal("-10.00")));

        assertThrows(InvalidAmountException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), openPayload, BigDecimal.ZERO));

        assertThrows(InvalidAmountException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), openPayload, null));
    }

    @Test
    void testRejectInsufficientCustomerFunds() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("99999.00"), // Customer only has 5000 PHP
                "PHP",
                "REF-INSUFFICIENT-01",
                "Luxury purchase"
        );

        assertThrows(InsufficientFundsException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), payload, null));
    }

    @Test
    void testRejectSelfPaymentAttempt() {
        // Seeded merchant attempts to pay their own merchant QR
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("50.00"),
                "PHP",
                "REF-SELF-01",
                "Self pay"
        );

        WalletException ex = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(seededMerchant.getUserId(), payload, null));
        assertTrue(ex.getMessage().contains("Self-payment"));
    }

    @Test
    void testRejectDuplicatePaymentSubmission() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("75.00"),
                "PHP",
                "REF-DUP-01",
                "Single use order"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, null);

        // First payment succeeds
        Transaction first = merchantQrService.confirmAndPay(preview, "1234");
        assertEquals(TransactionStatus.COMPLETED, first.getStatus());

        // Second payment with the exact same QR reference must be rejected as duplicate
        assertThrows(DuplicateTransactionException.class, () ->
                merchantQrService.confirmAndPay(preview, "1234"));
    }

    @Test
    void testRejectInvalidPin() {
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("120.00"),
                "PHP",
                "REF-WRONG-PIN-01",
                "Coffee"
        );

        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), payload, null);

        // Enter incorrect PIN "9999" (customer PIN is "1234")
        assertThrows(WalletException.class, () ->
                merchantQrService.confirmAndPay(preview, "9999"));
    }

    // --- 7. Delimiter Escaping, Strict Grammar & Image Bounds ---

    @Test
    void testDelimiterEscapingRoundTripAndStrictPayloadRejections(@TempDir Path tempDir) {
        MerchantQrPayload specialPayload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                "Cafe | Bean = 100%",
                new BigDecimal("125.00"),
                "PHP",
                "REF-DELIM-01",
                "Coffee | Large = Extra 50%"
        );

        Path qrFile = tempDir.resolve("delim_qr.png");
        merchantQrService.exportQrImage(specialPayload, qrFile);
        MerchantQrPayload imported = merchantQrService.importQrImage(qrFile);
        assertEquals("Cafe | Bean = 100%", imported.getMerchantName());
        assertEquals("Coffee | Large = Extra 50%", imported.getNote());
        assertEquals(0, new BigDecimal("125.00").compareTo(imported.getAmount()));

        String validEncoded = specialPayload.encode();
        // Trailing garbage or unknown key must be rejected
        assertThrows(MalformedQrException.class,
                () -> MerchantQrPayload.parse(validEncoded + "|garbage|evil=injected"));

        // Unknown key even with recalculated valid CRC must be rejected
        String withUnknownKey = buildSignedPayload(
                "mid=" + seededMerchant.getUserId()
                        + "|mwid=" + seededMerchantWallet.getWalletId()
                        + "|name=Store|amt=50.00|curr=PHP|ref=REF-UNK-1|evil=1");
        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(withUnknownKey));

        // Duplicate key even with valid CRC must be rejected
        String withDupKey = buildSignedPayload(
                "mid=" + seededMerchant.getUserId()
                        + "|mwid=" + seededMerchantWallet.getWalletId()
                        + "|name=Store|amt=50.00|amt=60.00|curr=PHP|ref=REF-DUPK-1");
        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(withDupKey));

        // Missing curr even with valid CRC must be rejected
        String missingCurr = buildSignedPayload(
                "mid=" + seededMerchant.getUserId()
                        + "|mwid=" + seededMerchantWallet.getWalletId()
                        + "|name=Store|amt=50.00|ref=REF-NOCURR-1");
        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(missingCurr));

        // Missing name even with valid CRC must be rejected
        String missingName = buildSignedPayload(
                "mid=" + seededMerchant.getUserId()
                        + "|mwid=" + seededMerchantWallet.getWalletId()
                        + "|amt=50.00|curr=PHP|ref=REF-NONAME-1");
        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(missingName));

        // Over-precision amount (scale > 2) even with valid CRC must be rejected
        String overScale = buildSignedPayload(
                "mid=" + seededMerchant.getUserId()
                        + "|mwid=" + seededMerchantWallet.getWalletId()
                        + "|name=Store|amt=100.004|curr=PHP|ref=REF-SCALE-2");
        assertThrows(MalformedQrException.class, () -> MerchantQrPayload.parse(overScale));
    }

    @Test
    void testSmallCraftedPngAndOversizedPayloadRejectedCleanly(@TempDir Path tempDir) throws IOException {
        // Craft a 20x20 PNG (400 pixels) declaring OTOGQR + len=500 -> must throw MalformedQrException, not AIOOBE
        BufferedImage small = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        byte[] headerAndLen = new byte[]{'O', 'T', 'O', 'G', 'Q', 'R', 0x00, 0x00, 0x01, (byte) 0xF4};
        for (int i = 0; i < headerAndLen.length; i++) {
            int x = i % small.getWidth();
            int y = (small.getHeight() - 1) - (i / small.getWidth());
            int v = headerAndLen[i] & 0xFF;
            small.setRGB(x, y, (0xFF << 24) | (v << 16) | (v << 8) | v);
        }
        Path smallFile = tempDir.resolve("crafted_small.png");
        ImageIO.write(small, "PNG", smallFile.toFile());
        assertThrows(MalformedQrException.class, () -> merchantQrService.importQrImage(smallFile));

        // Oversized payload > MAX_PAYLOAD_BYTES rejected consistently on creation
        String hugeNote = "A".repeat(MerchantQrPayload.MAX_PAYLOAD_BYTES + 200);
        assertThrows(MalformedQrException.class, () -> new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("10.00"),
                "PHP",
                "REF-HUGE-01",
                hugeNote
        ));
    }

    // --- 8. Customer Status, Wallet Currency, Fixed-Amount Conflict, Duplicate Preview & Padded PIN ---

    @Test
    void testCustomerStatusCurrencyFixedAmountConflictDuplicatePreviewAndTrimmedPin() {
        MerchantQrPayload fixedPayload = new MerchantQrPayload(
                seededMerchant.getUserId(),
                seededMerchantWallet.getWalletId(),
                seededMerchant.getName(),
                new BigDecimal("100.00"),
                "PHP",
                "REF-VAL-01",
                "Validated order"
        );

        // Conflicting inputAmount on fixed QR must be rejected
        assertThrows(InvalidAmountException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), fixedPayload, new BigDecimal("50.00")));
        assertThrows(InvalidAmountException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), fixedPayload, new BigDecimal("-10.00")));

        // Padded PIN " 1234 " succeeds cleanly
        MerchantPaymentPreview preview = merchantQrService.previewPayment(
                customer.getUserId(), fixedPayload, new BigDecimal("100.00"));
        String confirmText = preview.toConfirmationText();
        assertFalse(confirmText.contains(seededMerchant.getUserId()),
                "Confirmation preview must mask raw merchant user ID");
        Transaction settled = merchantQrService.confirmAndPay(preview, " 1234 ");
        assertEquals(TransactionStatus.COMPLETED, settled.getStatus());

        // Duplicate pre-check rejects already-settled QR BEFORE PIN entry
        assertThrows(DuplicateTransactionException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), fixedPayload, null));

        // Customer wallet currency mismatch rejected
        User usdUser = authService.registerUser("USD Customer", "09173334455", "usd@otog.ph", "Pass1234");
        Wallet usdWallet = walletService.getWalletByUserId(usdUser.getUserId());
        databaseManager.executeInTransaction(conn -> {
            usdWallet.setCurrency("USD");
            walletRepository.update(usdWallet, conn);
            return null;
        });
        MerchantQrPayload phpPayload = new MerchantQrPayload(
                seededMerchant.getUserId(), seededMerchantWallet.getWalletId(), seededMerchant.getName(),
                new BigDecimal("25.00"), "PHP", "REF-CURR-01", "Order");
        WalletException currEx = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(usdUser.getUserId(), phpPayload, null));
        assertTrue(currEx.getMessage().contains("Currency mismatch"));

        // LOCKED customer account rejected even with ACTIVE wallet
        databaseManager.executeInTransaction(conn -> {
            userRepository.updateStatus(customer.getUserId(), UserStatus.LOCKED, conn);
            return null;
        });
        MerchantQrPayload lockPayload = new MerchantQrPayload(
                seededMerchant.getUserId(), seededMerchantWallet.getWalletId(), seededMerchant.getName(),
                new BigDecimal("25.00"), "PHP", "REF-LOCK-01", "Order");
        WalletException lockEx = assertThrows(WalletException.class, () ->
                merchantQrService.previewPayment(customer.getUserId(), lockPayload, null));
        assertTrue(lockEx.getMessage().contains("not active") || lockEx.getMessage().contains("LOCKED"));
    }

    // --- 9. ReceiptBuilder Fee Formula, QR Counterparty & Cached Image Export ---

    @Test
    void testReceiptBuilderNetCreditedWithFeeAndWalletServiceQrReceipt(@TempDir Path tempDir) {
        SendMoneyTransaction feeTxn = new SendMoneyTransaction(
                "TXN-QR-FEE", "REQ-QR-FEE01", customerWallet.getWalletId(), seededMerchantWallet.getWalletId(),
                new BigDecimal("100.00"), new BigDecimal("5.00"),
                seededMerchantWallet.getWalletId(), "QR Pay: OTOG Merchant Store - Order");
        feeTxn.setStatus(TransactionStatus.COMPLETED);

        String merchantReceipt = ReceiptBuilder.forMerchantPayment(
                feeTxn, "OTOG Merchant Store", "FEE01", true).build();
        assertTrue(merchantReceipt.contains("Net Credited"), "Merchant receipt must show Net Credited");
        assertTrue(merchantReceipt.contains("PHP 100.00"), "Net Credited must equal amount (100.00), not total (105.00)");
        assertFalse(merchantReceipt.contains("PHP 105.00"), "Merchant Net Credited must not include customer fee");

        String customerReceipt = ReceiptBuilder.forMerchantPayment(
                feeTxn, "OTOG Merchant Store", "FEE01", false).build();
        assertTrue(customerReceipt.contains("Total Paid"));
        assertTrue(customerReceipt.contains("PHP 105.00"));

        // QR counterparty branch is reachable even for SendMoneyTransaction
        String genericReceipt = feeTxn.getReceipt(customerWallet.getWalletId(), null);
        assertTrue(genericReceipt.contains("Counterparty") && genericReceipt.contains("OTOG Merchant Store"),
                "QR Pay description must populate merchant counterparty before generic SendMoney fallback");

        // Real settled QR payment returns merchant-formatted receipt via WalletService.getTransactionReceipt
        MerchantQrPayload payload = new MerchantQrPayload(
                seededMerchant.getUserId(), seededMerchantWallet.getWalletId(), seededMerchant.getName(),
                new BigDecimal("88.50"), "PHP", "REF-WSREC-01", "Pastry");
        BufferedImage rendered = merchantQrService.generateQrImage(payload);
        Path exportedCached = tempDir.resolve("cached_export.png");
        merchantQrService.exportQrImage(rendered, exportedCached);
        assertEquals("REF-WSREC-01", merchantQrService.importQrImage(exportedCached).getQrReference());

        MerchantPaymentPreview preview = merchantQrService.previewPayment(customer.getUserId(), payload, null);
        Transaction txn = merchantQrService.confirmAndPay(preview, "1234");
        String wsMerchantRec = walletService.getTransactionReceipt(seededMerchantWallet.getWalletId(), txn.getReferenceNumber());
        assertTrue(wsMerchantRec.contains("OTOG MERCHANT PAYMENT RECEIPT"));
        assertTrue(wsMerchantRec.contains("REF-WSREC-01"));
        String wsCustomerRec = walletService.getTransactionReceipt(customerWallet.getWalletId(), txn.getReferenceNumber());
        assertTrue(wsCustomerRec.contains("OTOG MERCHANT PURCHASE RECEIPT"));
    }

    // --- 10. Concurrency (Duplicate QR Race & Balance Race) ---

    @Test
    void testConcurrentDuplicateQrAndBalanceRaceProtection(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("merchant-qr-race.db");
        DatabaseManager fileDb = new DatabaseManager("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try {
            AuthServiceImpl raceAuth = new AuthServiceImpl(fileDb);
            WalletService raceWallet = new WalletService(fileDb, raceAuth);
            UserRepository raceUsers = new UserRepository(fileDb);
            MerchantQrService raceQr = new MerchantQrService(raceWallet, raceUsers, raceAuth);

            OtogConfig.MerchantSeedConfig seed = OtogConfig.getInstance().getMerchantSeedConfig();
            User raceMerchant = raceUsers.findById(seed.userId()).orElseThrow();
            Wallet raceMerchantWallet = raceWallet.getWallet(seed.walletId());
            BigDecimal merchantStart = raceMerchantWallet.getBalance();

            User raceCust = raceAuth.registerUser("Race Cust", "09178881122", "race@otog.ph", "RacePass123");
            Wallet raceCustWallet = raceWallet.getWalletByUserId(raceCust.getUserId());
            raceWallet.cashIn("REQ-RACE-SEED", raceCustWallet.getWalletId(), null,
                    new BigDecimal("150.00"), "Seed 150");
            SessionManager.getInstance().login(raceCust, raceWallet.getWallet(raceCustWallet.getWalletId()));
            raceAuth.setPin(raceCust.getUserId(), "RacePass123", "1234");

            MerchantQrPayload sameQr = new MerchantQrPayload(
                    raceMerchant.getUserId(), raceMerchantWallet.getWalletId(), raceMerchant.getName(),
                    new BigDecimal("100.00"), "PHP", "REF-RACE-SAME", "Race test");
            MerchantPaymentPreview samePreview = raceQr.previewPayment(raceCust.getUserId(), sameQr, null);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch startSame = new CountDownLatch(1);
                List<Callable<Boolean>> sameTasks = new ArrayList<>();
                AtomicInteger sameSuccess = new AtomicInteger();
                AtomicInteger sameDup = new AtomicInteger();
                for (int i = 0; i < 4; i++) {
                    sameTasks.add(() -> {
                        startSame.await(5, TimeUnit.SECONDS);
                        try {
                            raceQr.confirmAndPay(samePreview, "1234");
                            sameSuccess.incrementAndGet();
                            return true;
                        } catch (DuplicateTransactionException ex) {
                            sameDup.incrementAndGet();
                            return false;
                        }
                    });
                }
                List<Future<Boolean>> sameFutures = new ArrayList<>();
                for (Callable<Boolean> task : sameTasks) {
                    sameFutures.add(pool.submit(task));
                }
                startSame.countDown();
                for (Future<Boolean> f : sameFutures) {
                    f.get(15, TimeUnit.SECONDS);
                }
                assertEquals(1, sameSuccess.get(), "Exactly 1 thread may settle a single-use QR");
                assertEquals(3, sameDup.get(), "3 racing threads must receive DuplicateTransactionException");
                assertEquals(0, new BigDecimal("50.00").compareTo(raceWallet.getBalance(raceCustWallet.getWalletId())));
                assertEquals(0, merchantStart.add(new BigDecimal("100.00"))
                        .compareTo(raceWallet.getBalance(raceMerchantWallet.getWalletId())));

                // Now 4 distinct 50.00 QRs race for the remaining 50.00 balance
                CountDownLatch startBal = new CountDownLatch(1);
                AtomicInteger balSuccess = new AtomicInteger();
                AtomicInteger balInsufficient = new AtomicInteger();
                List<Future<Boolean>> balFutures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    final int idx = i;
                    MerchantQrPayload p = new MerchantQrPayload(
                            raceMerchant.getUserId(), raceMerchantWallet.getWalletId(), raceMerchant.getName(),
                            new BigDecimal("50.00"), "PHP", "REF-RACE-BAL-" + idx, "Balance race " + idx);
                    MerchantPaymentPreview prev = raceQr.previewPayment(raceCust.getUserId(), p, null);
                    balFutures.add(pool.submit(() -> {
                        startBal.await(15, TimeUnit.SECONDS);
                        try {
                            raceQr.confirmAndPay(prev, "1234");
                            balSuccess.incrementAndGet();
                            return true;
                        } catch (InsufficientFundsException ex) {
                            balInsufficient.incrementAndGet();
                            return false;
                        }
                    }));
                }
                startBal.countDown();
                for (Future<Boolean> f : balFutures) {
                    f.get(15, TimeUnit.SECONDS);
                }
                assertEquals(1, balSuccess.get(), "Exactly 1 thread may consume remaining PHP 50.00");
                assertEquals(3, balInsufficient.get(), "3 racing threads must receive InsufficientFundsException");
                assertEquals(0, BigDecimal.ZERO.compareTo(raceWallet.getBalance(raceCustWallet.getWalletId())));
                assertEquals(0, merchantStart.add(new BigDecimal("150.00"))
                        .compareTo(raceWallet.getBalance(raceMerchantWallet.getWalletId())));
            } finally {
                pool.shutdownNow();
            }
        } finally {
            SessionManager.getInstance().reset();
            fileDb.close();
        }
    }

    // --- 11. File-Backed Restart Persistence & CAP-08 Notifications + Statements ---

    @Test
    void testFileBackedRestartPersistenceAndCap08NotificationsAndStatements(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("merchant-qr-restart.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        String custUserId;
        String custWalletId;
        String merchUserId;
        String merchWalletId;
        BigDecimal merchBalanceBefore;

        DatabaseManager db1 = new DatabaseManager(jdbcUrl);
        try {
            AuthServiceImpl auth1 = new AuthServiceImpl(db1);
            WalletService ws1 = new WalletService(db1, auth1);
            UserRepository ur1 = new UserRepository(db1);
            MerchantQrService qr1 = new MerchantQrService(ws1, ur1, auth1);

            OtogConfig.MerchantSeedConfig seed = OtogConfig.getInstance().getMerchantSeedConfig();
            merchUserId = seed.userId();
            merchWalletId = seed.walletId();
            merchBalanceBefore = ws1.getBalance(merchWalletId);

            User c = auth1.registerUser("Persist Cust", "09174445566", "persist@otog.ph", "Secret123");
            custUserId = c.getUserId();
            Wallet cw = ws1.getWalletByUserId(custUserId);
            custWalletId = cw.getWalletId();
            ws1.cashIn("REQ-PERSIST-CI", custWalletId, null, new BigDecimal("600.00"), "Top up");
            SessionManager.getInstance().login(c, ws1.getWallet(custWalletId));
            auth1.setPin(custUserId, "Secret123", "2468");

            MerchantQrPayload payload = new MerchantQrPayload(
                    merchUserId, merchWalletId, seed.username(),
                    new BigDecimal("125.00"), "PHP", "REF-PERSIST-QR", "Persistent order");
            MerchantPaymentPreview prev = qr1.previewPayment(custUserId, payload, null);
            qr1.confirmAndPay(prev, "2468");
        } finally {
            SessionManager.getInstance().reset();
            db1.close();
        }

        // Reopen database from disk
        DatabaseManager db2 = new DatabaseManager(jdbcUrl);
        try {
            AuthServiceImpl auth2 = new AuthServiceImpl(db2);
            WalletService ws2 = new WalletService(db2, auth2);
            UserRepository ur2 = new UserRepository(db2);

            assertEquals(0, new BigDecimal("475.00").compareTo(ws2.getBalance(custWalletId)));
            assertEquals(0, merchBalanceBefore.add(new BigDecimal("125.00")).compareTo(ws2.getBalance(merchWalletId)));

            List<Notification> custNotifs = ws2.getNotifications(custUserId, false);
            assertTrue(custNotifs.stream().anyMatch(n -> n.getType() == NotificationType.TRANSFER_SENT),
                    "Customer must have persisted TRANSFER_SENT notification");
            List<Notification> merchNotifs = ws2.getNotifications(merchUserId, false);
            assertTrue(merchNotifs.stream().anyMatch(n -> n.getType() == NotificationType.TRANSFER_RECEIVED),
                    "Merchant must have persisted TRANSFER_RECEIVED notification");

            // CAP-08 Statement export & import round-trip (.txt and .csv)
            StatementFileService sfs = new StatementFileService();
            User reloadedCust = ur2.findById(custUserId).orElseThrow();
            Wallet reloadedCustWallet = ws2.getWallet(custWalletId);
            List<Transaction> txns = ws2.getRecentTransactions(custWalletId, 20);
            Path txtStmt = tempDir.resolve("qr-statement.txt");
            Path csvStmt = tempDir.resolve("qr-statement.csv");
            sfs.exportWalletStatement(reloadedCust, reloadedCustWallet, txns, txtStmt);
            sfs.exportWalletStatement(reloadedCust, reloadedCustWallet, txns, csvStmt);
            assertEquals(new BigDecimal("475.00"), sfs.importWalletStatement(txtStmt).getBalance());
            assertEquals(new BigDecimal("475.00"), sfs.importWalletStatement(csvStmt).getBalance());
        } finally {
            db2.close();
        }
    }

    // --- 12. MerchantQrPanel & Main Dashboard UI Flows + CAP-09 Role Isolation + PIN Lockout ---

    @Test
    void testProtectedProxyRoleIsolationUiFlowsAndPinLockout(@TempDir Path tempDir) throws Exception {
        WalletOperations protectedOps = ProtectedWalletOperationsProxy.wrap(walletService);

        // 1. Standard USER session on Main dashboard
        SessionManager.getInstance().login(customer, walletService.getWallet(customerWallet.getWalletId()));
        Main customerDashboard = new Main(protectedOps, authService);
        try {
            customerDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);
            MerchantQrPanel qrPanel = customerDashboard.getMerchantQrPanel();
            assertNotNull(qrPanel);
            assertFalse(qrPanel.isMerchantTerminalVisible(),
                    "CAP-09: Standard USER must not see Merchant Terminal & Sales tab");

            // Capture confirmation previews without blocking on modal Swing dialogs
            List<String> shownConfirmations = new ArrayList<>();
            qrPanel.setConfirmDialogCallback((title, msg) -> {
                shownConfirmations.add(title + "\n" + msg);
                return javax.swing.JOptionPane.OK_OPTION;
            });

            // Even programmatic refreshMerchantSalesAsync must be blocked for USER
            ExecutionException denied = assertThrows(ExecutionException.class, () ->
                    qrPanel.refreshMerchantSalesAsync().get(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, denied.getCause());

            // Generate a QR with a SPOOFED merchant name in the payload
            MerchantQrPayload spoofedNamePayload = new MerchantQrPayload(
                    seededMerchant.getUserId(), seededMerchantWallet.getWalletId(),
                    "EVIL SPOOFED NAME", null, "PHP", "REF-UI-SPOOF-01", "UI Open Order");
            Path spoofedQrFile = tempDir.resolve("spoofed_qr.png");
            merchantQrService.exportQrImage(spoofedNamePayload, spoofedQrFile);

            // Import validates against DB and displays DB-verified merchant name, NOT spoofed payload name
            qrPanel.importQrFileAsync(spoofedQrFile).get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> { });
            assertTrue(qrPanel.isLastOperationExecutedOffEdt(), "QR import must execute off EDT");
            assertTrue(qrPanel.getConfirmPayButton().isEnabled());
            String confirmText = qrPanel.getConfirmationArea().getText();
            assertTrue(confirmText.contains(seededMerchant.getName()), "Must display DB-verified merchant name");
            assertFalse(confirmText.contains("EVIL SPOOFED NAME"), "Must never display untrusted spoofed merchant name");

            // Cancelling confirmation dialog aborts cleanly without debiting
            qrPanel.setConfirmDialogCallback((title, msg) -> {
                shownConfirmations.add("CANCEL:" + msg);
                return javax.swing.JOptionPane.CANCEL_OPTION;
            });
            assertNull(qrPanel.submitPaymentAsync("110.00", "1234").get(5, TimeUnit.SECONDS));
            assertEquals(0, new BigDecimal("5000.00").compareTo(walletService.getBalance(customerWallet.getWalletId())));
            assertTrue(shownConfirmations.stream().anyMatch(s -> s.contains("MERCHANT PAYMENT CONFIRMATION")));

            // Confirming open-amount QR runs off-EDT -> single-use payload cleared & button disabled
            qrPanel.setConfirmDialogCallback((title, msg) -> {
                shownConfirmations.add("OK:" + msg);
                return javax.swing.JOptionPane.OK_OPTION;
            });
            Transaction paid = qrPanel.submitPaymentAsync("110.00", "1234").get(5, TimeUnit.SECONDS);
            assertNotNull(paid);
            assertEquals(TransactionStatus.COMPLETED, paid.getStatus());
            assertTrue(qrPanel.isLastOperationExecutedOffEdt(), "QR payment must execute off EDT");
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(qrPanel.getConfirmPayButton().isEnabled(),
                    "Confirm & Pay button must be disabled after single-use settlement");
            assertNull(qrPanel.getLoadedPayload(), "Loaded payload must be cleared after settlement");

            // Inactive/suspended merchant QR rejected immediately during import
            databaseManager.executeInTransaction(conn -> {
                userRepository.updateStatus(seededMerchant.getUserId(), UserStatus.SUSPENDED, conn);
                return null;
            });
            MerchantQrPayload inactivePayload = new MerchantQrPayload(
                    seededMerchant.getUserId(), seededMerchantWallet.getWalletId(),
                    seededMerchant.getName(), new BigDecimal("20.00"), "PHP", "REF-UI-SUSP-01", "Test");
            Path inactiveFile = tempDir.resolve("inactive_qr.png");
            merchantQrService.exportQrImage(inactivePayload, inactiveFile);
            ExecutionException suspEx = assertThrows(ExecutionException.class, () ->
                    qrPanel.importQrFileAsync(inactiveFile).get(5, TimeUnit.SECONDS));
            assertInstanceOf(WalletException.class, suspEx.getCause());
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(qrPanel.getConfirmPayButton().isEnabled());

            // Reactivate merchant and test 3-attempt PIN lockout triggering Main logout callback
            databaseManager.executeInTransaction(conn -> {
                userRepository.updateStatus(seededMerchant.getUserId(), UserStatus.ACTIVE, conn);
                return null;
            });
            MerchantQrPayload lockoutQr = new MerchantQrPayload(
                    seededMerchant.getUserId(), seededMerchantWallet.getWalletId(),
                    seededMerchant.getName(), new BigDecimal("25.00"), "PHP", "REF-UI-LOCKOUT", "Lockout test");
            Path lockoutFile = tempDir.resolve("lockout_qr.png");
            merchantQrService.exportQrImage(lockoutQr, lockoutFile);
            qrPanel.importQrFileAsync(lockoutFile).get(5, TimeUnit.SECONDS);

            CountDownLatch logoutLatch = new CountDownLatch(1);
            customerDashboard.setOnLogoutCallback(logoutLatch::countDown);
            for (int attempt = 0; attempt < 3; attempt++) {
                ExecutionException badPin = assertThrows(ExecutionException.class, () ->
                        qrPanel.submitPaymentAsync("25.00", "9999").get(5, TimeUnit.SECONDS));
                assertInstanceOf(WalletException.class, badPin.getCause());
            }
            assertTrue(logoutLatch.await(5, TimeUnit.SECONDS),
                    "3 failed PIN attempts in MerchantQrPanel must trigger Main session termination");
            assertTrue(SessionManager.getInstance().isLockedOutByPin());
            assertFalse(SessionManager.getInstance().isLoggedIn());
        } finally {
            customerDashboard.dispose();
        }

        // 2. Custom MERCHANT session on Main dashboard: auto-loads QR-only sales, filters CASH_IN, real merchant name
        User customMerchant = authService.registerUser(
                "Bistro Uno", "09175556677", "bistro@otog.ph", "MerchPass123", UserRole.MERCHANT);
        Wallet customMerchantWallet = walletService.getWalletByUserId(customMerchant.getUserId());
        walletService.cashIn("REQ-MERCH-NONQR-CI", customMerchantWallet.getWalletId(), null,
                new BigDecimal("500.00"), "Merchant non-QR top-up");

        // Another active customer pays customMerchant via QR
        User payer = authService.registerUser("Payer Two", "09176667788", "payer2@otog.ph", "PayerPass123");
        Wallet payerWallet = walletService.getWalletByUserId(payer.getUserId());
        walletService.cashIn("REQ-PAYER2-CI", payerWallet.getWalletId(), null, new BigDecimal("1000.00"), "Seed");
        SessionManager.getInstance().login(payer, walletService.getWallet(payerWallet.getWalletId()));
        authService.setPin(payer.getUserId(), "PayerPass123", "1111");
        MerchantQrPayload customQr = new MerchantQrPayload(
                customMerchant.getUserId(), customMerchantWallet.getWalletId(),
                customMerchant.getName(), new BigDecimal("199.00"), "PHP", "REF-BISTRO-01", "Latte");
        merchantQrService.confirmAndPay(merchantQrService.previewPayment(payer.getUserId(), customQr, null), "1111");

        // Log in as customMerchant and open Main dashboard
        SessionManager.getInstance().login(customMerchant, walletService.getWallet(customMerchantWallet.getWalletId()));
        Main merchantDashboard = new Main(protectedOps, authService);
        try {
            merchantDashboard.getInitialLoadFuture().get(5, TimeUnit.SECONDS);
            MerchantQrPanel merchantPanel = merchantDashboard.getMerchantQrPanel();
            assertTrue(merchantPanel.isMerchantTerminalVisible(),
                    "MERCHANT user must see Merchant Terminal & Sales tab");

            // Wait briefly for auto-triggered sales refresh or run explicitly to verify QR-only filter
            List<Transaction> sales = merchantPanel.refreshMerchantSalesAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, sales.size(), "Non-QR CASH_IN deposits must be filtered out of Merchant Sales");
            assertEquals("REQ-QR-REF-BISTRO-01", sales.get(0).getReferenceNumber());

            SwingUtilities.invokeAndWait(() -> merchantPanel.getMerchantSalesTable().setRowSelectionInterval(0, 0));
            String selectedReceipt = merchantPanel.buildSelectedSaleReceiptText();
            assertNotNull(selectedReceipt);
            assertTrue(selectedReceipt.contains("Bistro Uno"),
                    "Merchant sale receipt must use the active merchant's real name, not hardcoded seed name");
            assertTrue(selectedReceipt.contains("QR Ref") && selectedReceipt.contains("REF-BISTRO-01"));
            assertFalse(selectedReceipt.contains("QR Ref         : REQ-QR-"),
                    "QR Ref must strip REQ-QR- prefix");

            // Generate & export QR off EDT
            MerchantQrPayload gen = merchantPanel.generateQrAsync("75.00", "Muffin | Fresh").get(5, TimeUnit.SECONDS);
            assertTrue(merchantPanel.isLastOperationExecutedOffEdt());
            assertEquals("Bistro Uno", gen.getMerchantName());
            Path savedPng = tempDir.resolve("bistro_exported.png");
            merchantPanel.exportGeneratedQrAsync(savedPng).get(5, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(savedPng));
            assertEquals("Muffin | Fresh", merchantQrService.importQrImage(savedPng).getNote());
        } finally {
            merchantDashboard.dispose();
        }
    }

    private static String buildSignedPayload(String canonicalBody) {
        CRC32 crc = new CRC32();
        crc.update(canonicalBody.getBytes(StandardCharsets.UTF_8));
        String hex = Long.toHexString(crc.getValue()).toUpperCase(java.util.Locale.ROOT);
        return MerchantQrPayload.QR_HEADER + "|" + canonicalBody + "|crc=" + hex;
    }
}
