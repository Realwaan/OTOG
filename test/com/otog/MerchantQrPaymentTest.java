package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.DuplicateTransactionException;
import com.otog.exception.EntityNotFoundException;
import com.otog.exception.InsufficientFundsException;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.MalformedQrException;
import com.otog.exception.WalletException;
import com.otog.model.MerchantQrPayload;
import com.otog.model.ReceiptBuilder;
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
import com.otog.service.SessionManager;
import com.otog.service.WalletService;
import com.otog.util.IdGenerator;
import com.otog.util.OtogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
}
