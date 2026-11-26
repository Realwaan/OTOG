package com.otog;

import com.otog.database.DatabaseManager;
import com.otog.exception.InvalidAmountException;
import com.otog.exception.WalletException;
import com.otog.model.BillPaymentTransaction;
import com.otog.model.CashInOutTransaction;
import com.otog.model.CompositeFeePolicy;
import com.otog.model.FlatFee;
import com.otog.model.InvestMoneyTransaction;
import com.otog.model.LoanTransaction;
import com.otog.model.PaymentMethod;
import com.otog.model.PaymentMethodType;
import com.otog.model.PercentageFee;
import com.otog.model.ReceiptBuilder;
import com.otog.model.ReceiptComponent;
import com.otog.model.ReceiptLine;
import com.otog.model.ReceiptSection;
import com.otog.model.SendMoneyTransaction;
import com.otog.model.Transaction;
import com.otog.model.TransactionBuilder;
import com.otog.model.TransactionFactory;
import com.otog.model.TransactionPrototype;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.UserStatus;
import com.otog.model.Wallet;
import com.otog.service.AdminService;
import com.otog.service.AuditingBankAdapterDecorator;
import com.otog.service.AuthServiceImpl;
import com.otog.service.BankAdapter;
import com.otog.service.BankAdapterDecorator;
import com.otog.service.BankApiClient;
import com.otog.service.BankResponseStatus;
import com.otog.service.CsvDocumentFormat;
import com.otog.service.MockBankAdapter;
import com.otog.service.OtpService;
import com.otog.service.PaymentProviderFactory;
import com.otog.service.PipeTextDocumentFormat;
import com.otog.service.ProtectedAdminServiceProxy;
import com.otog.service.ProtectedWalletOperationsProxy;
import com.otog.service.SendMoneyService;
import com.otog.service.SessionManager;
import com.otog.service.StatementFileService;
import com.otog.service.TransactionReceiptDocument;
import com.otog.service.ValidatingBankAdapterDecorator;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.service.WalletStatementDocument;
import com.otog.ui.CashInOutPanel;
import com.otog.ui.Main;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies externalized configuration (no hardcoded limits/channels/styles/seeds)
 * and all 5 Creational + 7 Structural GoF Design Patterns implemented in OTOG.
 */
public class DesignPatternsAndConfigTest {

    private DatabaseManager databaseManager;
    private AuthServiceImpl authService;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
        UiTheme.clearCache();
        SessionManager.getInstance().reset();

        databaseManager = new DatabaseManager("jdbc:sqlite::memory:");
        authService = new AuthServiceImpl(databaseManager);
        walletService = authService.getWalletService();
    }

    @AfterEach
    void tearDown() {
        OtogConfig.getInstance().clearOverrides();
        OtogConfig.getInstance().reload();
        UiTheme.clearCache();
        SessionManager.getInstance().reset();
        databaseManager.close();
    }

    @Test
    void testOtogConfigSingletonExternalFileAndDynamicOverrides(@TempDir Path tempDir) throws Exception {
        OtogConfig config = OtogConfig.getInstance();
        assertSame(config, OtogConfig.getInstance());
        assertEquals("PHP", config.getDefaultCurrency());
        assertEquals(new BigDecimal("1.00"), config.getSendMinAmount());
        assertEquals(new BigDecimal("50000.00"), config.getSendMaxAmount());
        assertEquals(6, config.getOtpLength());
        assertFalse(config.getCashChannels().isEmpty());
        assertEquals(3, config.getDemoPaymentMethods().size());

        // Override business rules dynamically and verify services pick them up
        config.setOverride("send.min.amount", "25.00");
        config.setOverride("send.max.amount", "1000.00");
        config.setOverride("send.default.fee.flat", "5.00");
        config.setOverride("send.default.fee.percent", "0.01");
        config.setOverride("otp.length", "8");
        config.setOverride("investment.default.return.rate", "0.12");

        User sender = authService.registerUser("Config Sender", "09173000001", "csender@otog.ph", "Secret123");
        User receiver = authService.registerUser("Config Receiver", "09173000002", "creceiver@otog.ph", "Secret123");
        Wallet senderWallet = walletService.getWalletByUserId(sender.getUserId());
        walletService.cashIn("REQ-CFG-FUND", senderWallet.getWalletId(), null, new BigDecimal("2000.00"), "Seed");

        SendMoneyService sendService = walletService.createSendMoneyService(authService);
        assertThrows(InvalidAmountException.class,
                () -> sendService.preview(sender.getUserId(), receiver.getMobileNumber(), new BigDecimal("20.00"), null, "Below override"));
        assertThrows(InvalidAmountException.class,
                () -> sendService.preview(sender.getUserId(), receiver.getMobileNumber(), new BigDecimal("1500.00"), null, "Above override"));

        // When supplied fee is null, CompositeFeePolicy.fromConfig calculates 5.00 + (200.00 * 0.01) = 7.00
        SendMoneyService.SendMoneyPreview preview = sendService.preview(
                sender.getUserId(), receiver.getMobileNumber(), new BigDecimal("200.00"), null, "Override fee");
        assertEquals(new BigDecimal("7.00"), preview.getFee());
        assertEquals(new BigDecimal("207.00"), preview.getTotalDebit());

        // OTP length override takes effect dynamically
        OtpService otpService = new OtpService();
        String otp8 = otpService.generateOtp(sender.getUserId());
        assertTrue(otp8.matches("^[0-9]{8}$"), "OTP length should honor externalized config override: " + otp8);

        // Investment rate override takes effect dynamically
        InvestMoneyTransaction inv = TransactionFactory.createInvestment(
                "REQ-INV-CFG", senderWallet.getWalletId(), new BigDecimal("1000.00"), BigDecimal.ZERO,
                "OTOG Treasury", new BigDecimal("10"), "Config rate check");
        assertEquals(new BigDecimal("120.00"), inv.getReturns());

        // Reloading from a custom external properties file resets/applies file values
        Path customProps = tempDir.resolve("custom-otog.properties");
        Files.writeString(customProps, "currency.default=USD\ncash.channels=CustomBank,OTC\n", StandardCharsets.UTF_8);
        config.clearOverrides();
        config.reloadFrom(customProps);
        assertEquals("USD", config.getDefaultCurrency());
        assertEquals(List.of("CustomBank", "OTC"), config.getCashChannels());
    }

    @Test
    void testFactoryMethodBuilderAndPrototypeAcrossAllTransactionTypes() {
        User alice = authService.registerUser("Proto Alice", "09174000001", "palice@otog.ph", "Secret123");
        User bob = authService.registerUser("Proto Bob", "09174000002", "pbob@otog.ph", "Secret123");
        Wallet aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        Wallet bobWallet = walletService.getWalletByUserId(bob.getUserId());

        // Factory Method + Builder for all 4 specialized subclasses
        SendMoneyTransaction send = TransactionFactory.createSendMoney(
                "REQ-FM-SEND", aliceWallet.getWalletId(), bobWallet.getWalletId(),
                new BigDecimal("150.00"), new BigDecimal("2.50"), bob.getMobileNumber(), "Rent share");
        send.send();

        CashInOutTransaction cash = TransactionFactory.createCashOut(
                "TXN-FM-COUT", "REQ-FM-COUT", aliceWallet.getWalletId(),
                new BigDecimal("300.00"), new BigDecimal("5.00"), "GCash", "BNK-99", "Cash out test");
        cash.execute();

        LoanTransaction loan = TransactionFactory.createLoan(
                "REQ-FM-LOAN", aliceWallet.getWalletId(), new BigDecimal("2500.00"),
                new BigDecimal("0.04"), 6, LocalDate.of(2027, 5, 1), "Quick loan");
        loan.approve();

        InvestMoneyTransaction invest = TransactionFactory.createInvestment(
                "REQ-FM-INV", aliceWallet.getWalletId(), new BigDecimal("1000.00"),
                new BigDecimal("10.00"), "Balanced Growth Fund", new BigDecimal("25"), "Monthly SIP");
        invest.invest();

        BillPaymentTransaction bill = TransactionFactory.createBillPayment(
                "REQ-FM-BILL", aliceWallet.getWalletId(), new BigDecimal("500.00"),
                new BigDecimal("15.00"), "Meralco", "1234567890", "Electricity bill");
        bill.setStatus(TransactionStatus.COMPLETED);

        assertEquals(TransactionType.CASH_IN, TransactionFactory.createEmptyForType(TransactionType.CASH_IN).getType());
        assertInstanceOf(LoanTransaction.class, TransactionFactory.createEmptyForType(TransactionType.LOAN_REPAYMENT));
        assertInstanceOf(InvestMoneyTransaction.class, TransactionFactory.createEmptyForType(TransactionType.INVESTMENT_REDEEM));
        assertInstanceOf(BillPaymentTransaction.class, TransactionFactory.createEmptyForType(TransactionType.BILL_PAYMENT));

        // Builder missing-field guard check
        assertThrows(IllegalStateException.class, () -> new TransactionBuilder().buildCashInOut());
        assertThrows(IllegalStateException.class, () -> new TransactionBuilder().buildLoan());
        assertThrows(IllegalStateException.class, () -> new TransactionBuilder().buildInvestment());
        assertThrows(IllegalStateException.class, () -> new TransactionBuilder().buildBillPayment());

        // Prototype cloning preserves domain data while resetting lifecycle & idempotency IDs
        List<TransactionPrototype> prototypes = List.of(send, cash, loan, invest, bill);
        for (int i = 0; i < prototypes.size(); i++) {
            Transaction original = (Transaction) prototypes.get(i);
            String newRef = "REQ-CLONE-" + i;
            Transaction clone = prototypes.get(i).copyAsDraft(newRef);

            assertSame(original.getClass(), clone.getClass());
            assertNotSame(original, clone);
            assertNotEquals(original.getTransactionId(), clone.getTransactionId());
            assertEquals(newRef, clone.getReferenceNumber());
            assertEquals(TransactionStatus.PENDING, clone.getStatus());
            assertNull(clone.getCompletedAt());
            assertNull(clone.getProviderReference());
            assertNull(clone.getProviderStatus());
            assertEquals(original.getAmount(), clone.getAmount());
            assertEquals(original.getFee(), clone.getFee());
            assertEquals(original.getDescription(), clone.getDescription());
            assertEquals(TransactionStatus.COMPLETED, original.getStatus(), "Original prototype must remain COMPLETED");
        }
    }

    @Test
    void testCompositeReceiptBuilderAndCompositeFeePolicy() {
        CompositeFeePolicy feePolicy = new CompositeFeePolicy("Transfer Tier Fee")
                .add(new FlatFee("Processing Fee", new BigDecimal("12.00")))
                .add(new PercentageFee("Network 1.5%", new BigDecimal("0.015")))
                .add(new CompositeFeePolicy("Partner Surcharge").add(new FlatFee("Clearing", new BigDecimal("3.00"))));

        // 12.00 + (1000.00 * 0.015 = 15.00) + 3.00 = 30.00
        assertEquals(new BigDecimal("30.00"), feePolicy.calculate(new BigDecimal("1000.00")));
        assertEquals(3, feePolicy.getComponents().size());

        ReceiptSection customSection = new ReceiptSection(null, true, false);
        customSection.add(new ReceiptLine("Promo Code", "OTOG2026"));

        String rendered = new ReceiptBuilder()
                .banner("CUSTOM RECEIPT")
                .line("Reference", "REQ-COMP-01")
                .section(customSection)
                .moneyLine("Fee Total", feePolicy.calculate(new BigDecimal("1000.00")), "PHP")
                .build();
        assertTrue(rendered.contains("CUSTOM RECEIPT"));
        assertTrue(rendered.contains("Promo Code:   OTOG2026"));
        assertTrue(rendered.contains("Fee Total:    PHP 30.00"));

        LoanTransaction loan = TransactionFactory.createLoan(
                "REQ-LOAN-RCPT", "WLT-1", new BigDecimal("1500.00"),
                new BigDecimal("0.03"), 3, LocalDate.of(2027, 2, 1), "Loan receipt");
        ReceiptComponent component = loan.buildReceiptComponent();
        assertNotNull(component);
        String loanReceipt = loan.getReceipt();
        assertTrue(loanReceipt.contains("Principal:"));
        assertTrue(loanReceipt.contains("Term:"));
        assertTrue(loanReceipt.contains("Due Date:"));
    }

    @Test
    void testAbstractFactoryDecoratorsAndAdapterUnwrapping() {
        PaymentProviderFactory factory = PaymentProviderFactory.forMode(BankResponseStatus.TIMEOUT, BankResponseStatus.SUCCESS);
        BankAdapter decoratedAdapter = factory.createBankAdapter();
        BankApiClient client = factory.createBankApiClient();
        assertNotNull(factory.createFeePolicy());

        assertInstanceOf(AuditingBankAdapterDecorator.class, decoratedAdapter);
        AuditingBankAdapterDecorator auditing = BankAdapterDecorator
                .findAdapter(client.getAdapter(), AuditingBankAdapterDecorator.class)
                .orElseThrow();
        ValidatingBankAdapterDecorator validating = BankAdapterDecorator
                .findAdapter(client.getAdapter(), ValidatingBankAdapterDecorator.class)
                .orElseThrow();
        MockBankAdapter rootMock = BankAdapterDecorator
                .findAdapter(client.getAdapter(), MockBankAdapter.class)
                .orElseThrow();

        assertSame(rootMock, BankAdapterDecorator.unwrapRoot(client.getAdapter()));
        assertInstanceOf(MockBankAdapter.class, BankAdapterDecorator.unwrapRoot(decoratedAdapter));
        assertNotNull(validating);

        PaymentMethod pm = new PaymentMethod("PM-1", "USR-1", PaymentMethodType.BANK_ACCOUNT, "BDO Unibank", "Juan", "1234");
        assertThrows(IllegalArgumentException.class, () -> client.cashIn("  ", pm, new BigDecimal("100.00")));
        assertThrows(InvalidAmountException.class, () -> client.cashOut("REQ-BAD", pm, BigDecimal.ZERO));

        client.cashIn("REQ-DEC-01", pm, new BigDecimal("250.00"));
        client.setMode(BankResponseStatus.SUCCESS);
        client.checkStatus("REQ-DEC-01", "BNK-1");

        assertEquals(BankResponseStatus.SUCCESS, rootMock.getMode());
        List<AuditingBankAdapterDecorator.BankCallAudit> audits = auditing.getAuditTrail();
        assertEquals(2, audits.size());
        assertEquals("CASH_IN", audits.get(0).operation());
        assertEquals(BankResponseStatus.TIMEOUT, audits.get(0).status());
        assertEquals("STATUS_QUERY", audits.get(1).operation());
        assertEquals(BankResponseStatus.SUCCESS, audits.get(1).status());
    }

    @Test
    void testBridgeStatementAndReceiptCsvAndPipeFormats(@TempDir Path tempDir) throws Exception {
        User owner = authService.registerUser("Bridge User", "09175000001", "bridge@otog.ph", "Secret123");
        Wallet wallet = walletService.getWalletByUserId(owner.getUserId());
        walletService.cashIn("REQ-BR-01", wallet.getWalletId(), null,
                new BigDecimal("1250.75"), "Top-up, \"with quotes\" and details");
        Wallet updated = walletService.getWalletByUserId(owner.getUserId());
        List<Transaction> txns = walletService.getRecentTransactions(wallet.getWalletId(), 10);

        StatementFileService fileService = new StatementFileService();

        // CSV Bridge path (.csv)
        Path csvStatement = tempDir.resolve("statement.csv");
        fileService.exportWalletStatement(owner, updated, txns, csvStatement);
        String csvRaw = Files.readString(csvStatement, StandardCharsets.UTF_8);
        assertTrue(csvRaw.startsWith(CsvDocumentFormat.STATEMENT_HEADER));
        assertFalse(csvRaw.contains("09175000001"), "Raw mobile must be masked in CSV");
        assertFalse(csvRaw.contains(wallet.getWalletId()), "Raw wallet ID must be masked in CSV");

        StatementFileService.LoadedStatement loadedCsv = fileService.importWalletStatement(csvStatement);
        assertEquals(CsvDocumentFormat.STATEMENT_HEADER, loadedCsv.getVersion());
        assertEquals(new BigDecimal("1250.75"), loadedCsv.getBalance());
        assertEquals(1, loadedCsv.getEntries().size());
        assertEquals("Top-up, \"with quotes\" and details", loadedCsv.getEntries().get(0).getDescription());

        // Receipt CSV Bridge path (.csv) & direct Bridge abstraction usage
        Path csvReceipt = tempDir.resolve("receipt.csv");
        fileService.exportReceipt(txns.get(0), csvReceipt);
        String importedCsvReceipt = fileService.importReceipt(csvReceipt);
        assertTrue(importedCsvReceipt.contains("REQ-BR-01"));

        WalletStatementDocument pipeDoc = new WalletStatementDocument(new PipeTextDocumentFormat());
        TransactionReceiptDocument csvDoc = new TransactionReceiptDocument(new CsvDocumentFormat());
        assertEquals("txt", pipeDoc.getFormat().getFileExtension());
        assertEquals("csv", csvDoc.getFormat().getFileExtension());

        // Corrupted CSV rejected
        Path badCsv = tempDir.resolve("bad.csv");
        Files.writeString(badCsv, "INVALID_CSV_HEADER\n", StandardCharsets.UTF_8);
        assertThrows(WalletException.class, () -> fileService.importWalletStatement(badCsv));
    }

    @Test
    void testProtectionProxiesEnforceSessionOwnershipAndRoles() {
        User alice = authService.registerUser("Proxy Alice", "09176000001", "proxyalice@otog.ph", "Secret123");
        User bob = authService.registerUser("Proxy Bob", "09176000002", "proxybob@otog.ph", "Secret123");
        Wallet aliceWallet = walletService.getWalletByUserId(alice.getUserId());
        Wallet bobWallet = walletService.getWalletByUserId(bob.getUserId());
        walletService.cashIn("REQ-PRX-FUND", bobWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed Bob");

        WalletOperations proxyOps = ProtectedWalletOperationsProxy.wrap(walletService);
        AdminService proxyAdmin = proxyOps.getAdminService();
        assertInstanceOf(ProtectedWalletOperationsProxy.class, proxyOps);
        assertInstanceOf(ProtectedAdminServiceProxy.class, proxyAdmin);

        // Unauthenticated requests blocked by Protection Proxy
        assertThrows(SecurityException.class, () -> proxyOps.getBalance(aliceWallet.getWalletId()));
        assertThrows(SecurityException.class, () -> proxyAdmin.getAllUsers(alice.getUserId()));

        // Alice logged in: allowed on her own wallet, blocked on Bob's wallet and Admin APIs
        SessionManager.getInstance().login(alice, aliceWallet);
        assertEquals(new BigDecimal("0.00"), proxyOps.getBalance(aliceWallet.getWalletId()));
        assertThrows(SecurityException.class, () -> proxyOps.getBalance(bobWallet.getWalletId()));
        assertThrows(SecurityException.class, () -> proxyOps.getRecentTransactions(bobWallet.getWalletId(), 10));
        assertThrows(SecurityException.class, () -> proxyOps.getPaymentMethodsByUserId(bob.getUserId()));
        assertThrows(SecurityException.class, () ->
                proxyOps.transfer("REQ-HIJACK", bobWallet.getWalletId(), aliceWallet.getWalletId(),
                        new BigDecimal("100.00"), BigDecimal.ZERO, "Cross-wallet attack"));
        assertThrows(SecurityException.class, () -> proxyAdmin.getAllWallets(alice.getUserId()));

        // SendMoneyService preview through proxy still resolves recipient wallet cleanly
        SendMoneyService proxiedSend = proxyOps.createSendMoneyService(authService);
        walletService.cashIn("REQ-PRX-A", aliceWallet.getWalletId(), null, new BigDecimal("500.00"), "Seed Alice");
        SendMoneyService.SendMoneyPreview preview = proxiedSend.preview(
                alice.getUserId(), bob.getMobileNumber(), new BigDecimal("50.00"), null, "Valid send");
        assertEquals(bobWallet.getWalletId(), preview.getRecipientWalletId());

        // Suspended caller blocked by Wallet Proxy
        alice.setStatus(UserStatus.SUSPENDED);
        assertThrows(WalletException.class, () -> proxyOps.getBalance(aliceWallet.getWalletId()));
        alice.setStatus(UserStatus.ACTIVE);

        // Main.withOwnedDatabase wraps its service in ProtectedWalletOperationsProxy
        User admin = walletService.getUserRepository().findById("USR-ADMIN-01").orElseThrow();
        SessionManager.getInstance().login(admin, walletService.getWalletByUserId(admin.getUserId()));
        assertEquals(UserRole.ADMIN, admin.getRole());
        DatabaseManager extraDb = new DatabaseManager("jdbc:sqlite::memory:");
        Main dashboard = Main.withOwnedDatabase(extraDb);
        try {
            assertInstanceOf(ProtectedWalletOperationsProxy.class, dashboard.getWalletService());
            assertInstanceOf(ProtectedAdminServiceProxy.class, dashboard.getWalletService().getAdminService());
        } finally {
            dashboard.dispose();
        }
    }

    @Test
    void testUiThemeFlyweightAndOverviewFormAndPanelSync() throws Exception {
        // Flyweight shares immutable instances
        Font f1 = UiTheme.font(UiTheme.FontRole.BANNER);
        Font f2 = UiTheme.font(UiTheme.FontRole.BANNER);
        Color c1 = UiTheme.color(UiTheme.ColorRole.SUCCESS);
        Color c2 = UiTheme.color(UiTheme.ColorRole.SUCCESS);
        assertSame(f1, f2);
        assertSame(c1, c2);
        assertSame(UiTheme.bottomDividerBorder(), UiTheme.bottomDividerBorder());
        assertSame(UiTheme.titledBorder("Recent Activity"), UiTheme.titledBorder("Recent Activity"));

        // Color override refreshes Flyweight color on clearCache()
        OtogConfig.getInstance().setOverride("ui.color.success", "#008855");
        UiTheme.clearCache();
        assertEquals(new Color(0x00, 0x88, 0x55), UiTheme.color(UiTheme.ColorRole.SUCCESS));

        // OverviewPanel.form contains all 4 CardLayout cards and interactive controls
        String overviewXml = Files.readString(Paths.get("src/com/otog/ui/OverviewPanel.form"), StandardCharsets.UTF_8);
        for (String cardName : List.of("LOADING", "EMPTY", "ERROR", "READY")) {
            assertTrue(overviewXml.contains("<card name=\"" + cardName + "\"/>"),
                    "OverviewPanel.form must define card " + cardName);
        }
        for (String binding : List.of("txtFilterKeyword", "tblTransactions", "btnCheckStatus", "btnViewReceipt", "btnExportReceipt", "lblActivityFeedback")) {
            assertTrue(overviewXml.contains("binding=\"" + binding + "\""),
                    "OverviewPanel.form must bind component " + binding);
        }

        // CashInOutPanel reads channels & operations from OtogConfig
        OtogConfig.getInstance().setOverride("cash.channels", "PayMaya,UnionBank");
        OtogConfig.getInstance().setOverride("cash.operations", "CASH_IN");
        CashInOutPanel panel = new CashInOutPanel(BankResponseStatus.SUCCESS);
        List<String> channels = new ArrayList<>();
        for (int i = 0; i < panel.getCashChannelComboBox().getItemCount(); i++) {
            channels.add(panel.getCashChannelComboBox().getItemAt(i));
        }
        assertEquals(List.of("PayMaya", "UnionBank"), channels);
        assertEquals(1, panel.getCashOperationComboBox().getItemCount());
        assertEquals(BankResponseStatus.values().length, panel.getProviderModeComboBox().getItemCount());
    }
}
