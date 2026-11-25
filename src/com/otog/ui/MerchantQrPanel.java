package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.MerchantQrPayload;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.MerchantQrService;
import com.otog.service.MerchantQrService.MerchantPaymentPreview;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Bound IntelliJ Swing form panel for Merchant QR Payments (CAP-11):
 * - Customer Flow: Import & validate QR image, confirm DB-verified recipient & amount, authorize payment with PIN, view receipt.
 * - Merchant Flow (MERCHANT/ADMIN only): Generate project QR code, export PNG image, view received QR payments & merchant receipts.
 */
public class MerchantQrPanel extends JPanel {

    private static final String[] SALES_COLUMNS = {"Txn ID", "Reference", "Date", "Customer", "Amount", "Status"};
    private static final String MERCHANT_TAB_TITLE = "Merchant Terminal & Sales";

    private final MerchantQrService qrService;
    private final WalletOperations walletService;
    private final Runnable backToOverviewCallback;
    private final Runnable paymentCompletedCallback;

    private BooleanSupplier beforeInteractiveSubmit;
    private BooleanSupplier pinConfiguredSupplier;
    private Runnable onPinNotSet;
    private Consumer<Throwable> onPaymentError;
    private Consumer<Boolean> offEdtObserver;
    private BiFunction<String, String, Integer> confirmDialogCallback;
    private final AtomicBoolean paymentInFlight = new AtomicBoolean(false);
    private volatile boolean lastOperationExecutedOffEdt;

    private MerchantQrPayload loadedPayload;
    private User verifiedMerchant;
    private MerchantQrPayload generatedPayload;
    private BufferedImage lastGeneratedImage;
    private List<Transaction> loadedMerchantSales = Collections.emptyList();

    // Bound UI components
    private JPanel pnlRoot;
    private JTabbedPane tabsQr;

    // Customer Pay Tab
    private JPanel pnlPayTab;
    private JButton btnImportQr;
    private JLabel lblImportedFile;
    private JLabel lblQrThumbnail;
    private JTextArea txtConfirmation;
    private JLabel lblPayAmount;
    private JTextField txtPayAmount;
    private JPasswordField txtPayPin;
    private JButton btnConfirmPay;
    private JButton btnClearPay;
    private JButton btnBackToOverview;
    private JLabel lblPayFeedback;
    private JTextArea txtReceipt;

    // Merchant Generator Tab
    private JPanel pnlMerchantTab;
    private JLabel lblGenAmount;
    private JTextField txtGenAmount;
    private JTextField txtGenNote;
    private JButton btnGenerateQr;
    private JButton btnSaveQr;
    private JLabel lblGeneratedQr;
    private JTable tblMerchantSales;
    private JButton btnViewMerchantReceipt;
    private JButton btnRefreshSales;
    private JLabel lblMerchantFeedback;
    private DefaultTableModel salesTableModel;

    public MerchantQrPanel() {
        this(null, null, null, null, null);
    }

    public MerchantQrPanel(MerchantQrService qrService,
                           WalletOperations walletService,
                           AuthService authService,
                           Runnable backToOverviewCallback) {
        this(qrService, walletService, authService, backToOverviewCallback, null);
    }

    public MerchantQrPanel(MerchantQrService qrService,
                           WalletOperations walletService,
                           AuthService authService,
                           Runnable backToOverviewCallback,
                           Runnable paymentCompletedCallback) {
        super(new BorderLayout());
        this.qrService = qrService;
        this.walletService = walletService;
        this.backToOverviewCallback = backToOverviewCallback;
        this.paymentCompletedCallback = paymentCompletedCallback;

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        applyRoleAccessControl();
        initActions();
    }

    private void ensureComponentsInitialized() {
        OtogConfig config = OtogConfig.getInstance();
        String currency = config.getDefaultCurrency();
        if (lblPayAmount != null) {
            lblPayAmount.setText("Payment Amount (" + currency + "):");
        }
        if (lblGenAmount != null) {
            lblGenAmount.setText("Amount (" + currency + ", Optional):");
        }
        if (salesTableModel == null) {
            salesTableModel = new DefaultTableModel(SALES_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblMerchantSales != null) {
            if (tblMerchantSales.getModel() != salesTableModel) {
                tblMerchantSales.setModel(salesTableModel);
            }
            tblMerchantSales.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            tblMerchantSales.setRowHeight(config.getTableRowHeight());
            tblMerchantSales.setAutoCreateRowSorter(true);
            tblMerchantSales.getTableHeader().setReorderingAllowed(false);
        }
        if (lblQrThumbnail != null) {
            lblQrThumbnail.setPreferredSize(new Dimension(120, 120));
            lblQrThumbnail.setBorder(BorderFactory.createLineBorder(UiTheme.color(UiTheme.ColorRole.BORDER)));
        }
        if (lblGeneratedQr != null) {
            lblGeneratedQr.setPreferredSize(new Dimension(160, 160));
            lblGeneratedQr.setBorder(BorderFactory.createLineBorder(UiTheme.color(UiTheme.ColorRole.BORDER)));
        }
        if (txtConfirmation != null) {
            txtConfirmation.setEditable(false);
            txtConfirmation.setFont(UiTheme.font(UiTheme.FontRole.MONO));
            txtConfirmation.setLineWrap(true);
            txtConfirmation.setWrapStyleWord(true);
        }
        if (txtReceipt != null) {
            txtReceipt.setEditable(false);
            txtReceipt.setFont(UiTheme.font(UiTheme.FontRole.MONO));
            txtReceipt.setLineWrap(true);
            txtReceipt.setWrapStyleWord(true);
        }
        if (txtPayPin != null) {
            txtPayPin.setToolTipText("Enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                    + " digit Transaction PIN to authorize merchant QR payment");
        }
        if (btnConfirmPay != null) {
            btnConfirmPay.setEnabled(false);
        }
        if (btnSaveQr != null) {
            btnSaveQr.setEnabled(false);
        }
        if (btnViewMerchantReceipt != null) {
            btnViewMerchantReceipt.setEnabled(false);
        }
        if (lblPayFeedback != null) {
            lblPayFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
        if (lblMerchantFeedback != null) {
            lblMerchantFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
    }

    /**
     * Enforces CAP-09 role boundaries: hides the Merchant Terminal & Sales sub-tab from standard USER accounts.
     */
    public void applyRoleAccessControl() {
        if (tabsQr == null || pnlMerchantTab == null) {
            return;
        }
        User currentUser = SessionManager.getInstance().getCurrentUser();
        boolean canAccessMerchantTerminal = currentUser == null
                || currentUser.getRole() == UserRole.MERCHANT
                || currentUser.isAdmin();
        int existingIndex = tabsQr.indexOfComponent(pnlMerchantTab);
        if (!canAccessMerchantTerminal && existingIndex >= 0) {
            tabsQr.removeTabAt(existingIndex);
        } else if (canAccessMerchantTerminal && existingIndex < 0) {
            tabsQr.addTab(MERCHANT_TAB_TITLE, pnlMerchantTab);
        }
    }

    public boolean isMerchantTerminalVisible() {
        return tabsQr != null && pnlMerchantTab != null && tabsQr.indexOfComponent(pnlMerchantTab) >= 0;
    }

    public void setBeforeInteractiveSubmit(BooleanSupplier beforeInteractiveSubmit) {
        this.beforeInteractiveSubmit = beforeInteractiveSubmit;
    }

    public void setPinConfiguredSupplier(BooleanSupplier pinConfiguredSupplier) {
        this.pinConfiguredSupplier = pinConfiguredSupplier;
    }

    public void setOnPinNotSet(Runnable onPinNotSet) {
        this.onPinNotSet = onPinNotSet;
    }

    public void setOnPaymentError(Consumer<Throwable> onPaymentError) {
        this.onPaymentError = onPaymentError;
    }

    public void setOffEdtObserver(Consumer<Boolean> offEdtObserver) {
        this.offEdtObserver = offEdtObserver;
    }

    public void setConfirmDialogCallback(BiFunction<String, String, Integer> confirmDialogCallback) {
        this.confirmDialogCallback = confirmDialogCallback;
    }

    public boolean isLastOperationExecutedOffEdt() {
        return lastOperationExecutedOffEdt;
    }

    private void initActions() {
        if (btnImportQr != null) {
            btnImportQr.addActionListener(e -> handleImportQrAction());
        }
        if (btnConfirmPay != null) {
            btnConfirmPay.addActionListener(e -> handleConfirmPayAction());
        }
        if (txtPayAmount != null) {
            txtPayAmount.addActionListener(e -> handleConfirmPayAction());
        }
        if (txtPayPin != null) {
            txtPayPin.addActionListener(e -> handleConfirmPayAction());
        }
        if (btnClearPay != null) {
            btnClearPay.addActionListener(e -> resetPayForm());
        }
        if (btnBackToOverview != null) {
            btnBackToOverview.addActionListener(e -> {
                if (backToOverviewCallback != null) {
                    backToOverviewCallback.run();
                }
            });
        }
        if (btnGenerateQr != null) {
            btnGenerateQr.addActionListener(e -> handleGenerateQrAction());
        }
        if (txtGenAmount != null) {
            txtGenAmount.addActionListener(e -> handleGenerateQrAction());
        }
        if (txtGenNote != null) {
            txtGenNote.addActionListener(e -> handleGenerateQrAction());
        }
        if (btnSaveQr != null) {
            btnSaveQr.addActionListener(e -> handleSaveQrAction());
        }
        if (btnRefreshSales != null) {
            btnRefreshSales.addActionListener(e -> refreshMerchantSalesAsync());
        }
        if (btnViewMerchantReceipt != null) {
            btnViewMerchantReceipt.addActionListener(e -> showSelectedSaleReceipt());
        }
        if (tabsQr != null) {
            tabsQr.addChangeListener(e -> {
                if (pnlMerchantTab != null && tabsQr.getSelectedComponent() == pnlMerchantTab) {
                    refreshMerchantSalesIfAuthorized();
                }
            });
        }
        if (tblMerchantSales != null) {
            tblMerchantSales.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && tblMerchantSales.getSelectedRow() >= 0) {
                        showSelectedSaleReceipt();
                    }
                }
            });
            tblMerchantSales.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    btnViewMerchantReceipt.setEnabled(tblMerchantSales.getSelectedRow() >= 0);
                }
            });
        }
    }

    // --- Customer Pay Actions ---

    private void handleImportQrAction() {
        if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select OTOG Merchant QR Image");
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png", "PNG"));

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        importQrFileAsync(chooser.getSelectedFile().toPath());
    }

    public CompletableFuture<MerchantQrPayload> importQrFileAsync(Path sourceFile) {
        CompletableFuture<MerchantQrPayload> future = new CompletableFuture<>();
        if (qrService == null) {
            IllegalStateException err = new IllegalStateException("MerchantQrService is not initialized.");
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }
        if (sourceFile == null) {
            IllegalArgumentException err = new IllegalArgumentException("QR source file cannot be null.");
            future.completeExceptionally(err);
            return future;
        }

        User currentUser = SessionManager.getInstance().getCurrentUser();
        if (currentUser == null) {
            SecurityException err = new SecurityException("Active authenticated user session is required.");
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }

        File file = sourceFile.toFile();
        runOnEdt(() -> {
            btnImportQr.setEnabled(false);
            btnConfirmPay.setEnabled(false);
            lblImportedFile.setText(file.getName());
            lblPayFeedback.setText("Reading and validating QR code image...");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        });

        SwingWorker<ImportOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected ImportOutcome doInBackground() throws Exception {
                recordOffEdt();
                SessionManager.getInstance().touchActivity();
                MerchantQrPayload payload = qrService.importQrImage(sourceFile);
                User merchant = qrService.validateImportForCustomer(currentUser.getUserId(), payload);
                BufferedImage thumbnail = null;
                BufferedImage img = ImageIO.read(file);
                if (img != null) {
                    Image scaled = img.getScaledInstance(120, 120, Image.SCALE_SMOOTH);
                    thumbnail = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = thumbnail.createGraphics();
                    g.drawImage(scaled, 0, 0, null);
                    g.dispose();
                }
                return new ImportOutcome(payload, merchant, thumbnail);
            }

            @Override
            protected void done() {
                btnImportQr.setEnabled(true);
                try {
                    ImportOutcome outcome = get();
                    MerchantQrPayload payload = outcome.payload();
                    User merchant = outcome.merchant();
                    loadedPayload = payload;
                    verifiedMerchant = merchant;

                    if (outcome.thumbnail() != null) {
                        lblQrThumbnail.setIcon(new ImageIcon(outcome.thumbnail()));
                        lblQrThumbnail.setText("");
                    }

                    // Render DB-verified merchant details (never raw untrusted payload name or raw user ID)
                    ReceiptBuilder previewBuilder = new ReceiptBuilder()
                            .banner("VERIFIED MERCHANT QR")
                            .labelWidth(15)
                            .line("Merchant", merchant.getName())
                            .maskedLine("Merchant ID", merchant.getUserId())
                            .maskedLine("Mobile", merchant.getMobileNumber())
                            .maskedLine("Terminal", payload.getMerchantWalletId())
                            .line("Currency", payload.getCurrency())
                            .line("QR Reference", payload.getQrReference());
                    if (payload.hasFixedAmount()) {
                        previewBuilder.line("Amount", MoneyUtil.format(payload.getAmount(), payload.getCurrency()) + " (Fixed)");
                        txtPayAmount.setText(payload.getAmount().toPlainString());
                        txtPayAmount.setEditable(false);
                    } else {
                        previewBuilder.line("Amount", "Open (Customer specifies amount)");
                        txtPayAmount.setText("");
                        txtPayAmount.setEditable(true);
                    }
                    previewBuilder.optionalLine("Note", payload.getNote());
                    txtConfirmation.setText(previewBuilder.build());
                    txtConfirmation.setCaretPosition(0);

                    btnConfirmPay.setEnabled(true);
                    lblPayFeedback.setText("Merchant verified! Review recipient details, enter PIN, and click Confirm & Pay.");
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    if (!payload.hasFixedAmount()) {
                        txtPayAmount.requestFocusInWindow();
                    } else {
                        txtPayPin.requestFocusInWindow();
                    }
                    future.complete(payload);
                } catch (Exception e) {
                    Throwable cause = unwrapCause(e);
                    loadedPayload = null;
                    verifiedMerchant = null;
                    btnConfirmPay.setEnabled(false);
                    lblQrThumbnail.setIcon(null);
                    lblQrThumbnail.setText("[ Invalid QR ]");
                    txtConfirmation.setText("QR validation failed: " + extractErrorMessage(cause));
                    lblPayFeedback.setText("Error: " + extractErrorMessage(cause));
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    if (onPaymentError != null) {
                        onPaymentError.accept(cause);
                    }
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    protected int showConfirmDialog(String title, String message) {
        if (confirmDialogCallback != null) {
            Integer result = confirmDialogCallback.apply(title, message);
            return result != null ? result : JOptionPane.CANCEL_OPTION;
        }
        if (GraphicsEnvironment.isHeadless()) {
            return JOptionPane.OK_OPTION;
        }
        JTextArea area = new JTextArea(message);
        area.setEditable(false);
        area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        area.setBackground(UIManager.getColor("Panel.background"));
        return JOptionPane.showConfirmDialog(
                this,
                area,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
    }

    private void handleConfirmPayAction() {
        if (!btnConfirmPay.isEnabled() || paymentInFlight.get()) {
            return;
        }
        if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
            return;
        }
        if (qrService == null) {
            lblPayFeedback.setText("MerchantQrService is not initialized.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }
        if (loadedPayload == null) {
            lblPayFeedback.setText("Please import a valid Merchant QR code image first.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }
        if (pinConfiguredSupplier != null && !pinConfiguredSupplier.getAsBoolean()) {
            txtPayPin.setText("");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            lblPayFeedback.setText("Transaction PIN is not set. Please set a Transaction PIN before paying via Merchant QR.");
            if (onPinNotSet != null) {
                onPinNotSet.run();
            }
            return;
        }

        String rawAmount = txtPayAmount.getText() != null ? txtPayAmount.getText().trim() : "";
        char[] pinChars = txtPayPin.getPassword();
        String rawPin = (pinChars != null && pinChars.length > 0) ? new String(pinChars).trim() : "";
        submitPaymentAsync(rawAmount, rawPin);
    }

    public CompletableFuture<Transaction> submitPaymentAsync(String rawAmountText, String rawPin) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        if (qrService == null) {
            IllegalStateException err = new IllegalStateException("MerchantQrService is not initialized.");
            future.completeExceptionally(err);
            return future;
        }
        if (loadedPayload == null) {
            IllegalStateException err = new IllegalStateException("Please import a valid Merchant QR code image first.");
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }
        User currentUser = SessionManager.getInstance().getCurrentUser();
        if (currentUser == null) {
            SecurityException err = new SecurityException("Active authenticated user session is required.");
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }

        String trimmedAmount = rawAmountText != null ? rawAmountText.trim() : "";
        BigDecimal inputAmount;
        try {
            inputAmount = trimmedAmount.isEmpty() ? null : new BigDecimal(trimmedAmount);
        } catch (NumberFormatException nfe) {
            IllegalArgumentException err = new IllegalArgumentException(
                    "Invalid amount format. Please enter a valid number (e.g. 150.00).", nfe);
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                txtPayAmount.requestFocusInWindow();
            });
            future.completeExceptionally(err);
            return future;
        }

        String cleanPin = rawPin != null ? rawPin.trim() : "";
        if (cleanPin.isEmpty()) {
            IllegalArgumentException err = new IllegalArgumentException("Transaction PIN is required to authorize payment.");
            runOnEdt(() -> {
                lblPayFeedback.setText(err.getMessage());
                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                txtPayPin.requestFocusInWindow();
            });
            future.completeExceptionally(err);
            return future;
        }

        if (!paymentInFlight.compareAndSet(false, true)) {
            future.complete(null);
            return future;
        }

        final MerchantQrPayload payload = loadedPayload;
        runOnEdt(() -> {
            btnConfirmPay.setEnabled(false);
            btnImportQr.setEnabled(false);
            txtPayPin.setText("");
            lblPayFeedback.setText("Validating payment preview...");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        });

        // Step 1: Validate and build preview off the EDT, then show recipient confirmation before debiting
        new SwingWorker<MerchantPaymentPreview, Void>() {
            @Override
            protected MerchantPaymentPreview doInBackground() {
                recordOffEdt();
                SessionManager.getInstance().touchActivity();
                return qrService.previewPayment(currentUser.getUserId(), payload, inputAmount);
            }

            @Override
            protected void done() {
                MerchantPaymentPreview preview;
                try {
                    preview = get();
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    paymentInFlight.set(false);
                    btnImportQr.setEnabled(true);
                    btnConfirmPay.setEnabled(loadedPayload != null);
                    lblPayFeedback.setText("Validation failed: " + extractErrorMessage(cause));
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    if (onPaymentError != null) {
                        onPaymentError.accept(cause);
                    }
                    future.completeExceptionally(cause);
                    return;
                }

                txtConfirmation.setText(preview.toConfirmationText());
                txtConfirmation.setCaretPosition(0);

                int choice = showConfirmDialog("Confirm Merchant QR Payment", preview.toConfirmationText());
                if (choice != JOptionPane.OK_OPTION) {
                    paymentInFlight.set(false);
                    btnImportQr.setEnabled(true);
                    btnConfirmPay.setEnabled(loadedPayload != null);
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                    lblPayFeedback.setText("Merchant QR payment cancelled.");
                    future.complete(null);
                    return;
                }

                lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblPayFeedback.setText("Authorizing and processing merchant payment...");

                // Step 2: Execute atomic transfer off the EDT
                new SwingWorker<Transaction, Void>() {
                    @Override
                    protected Transaction doInBackground() {
                        recordOffEdt();
                        SessionManager.getInstance().touchActivity();
                        return qrService.confirmAndPay(preview, cleanPin);
                    }

                    @Override
                    protected void done() {
                        paymentInFlight.set(false);
                        btnImportQr.setEnabled(true);
                        txtPayPin.setText("");
                        try {
                            Transaction txn = get();
                            // Clear single-use QR payload & lock submit button after successful settlement
                            loadedPayload = null;
                            verifiedMerchant = null;
                            btnConfirmPay.setEnabled(false);

                            lblPayFeedback.setText("Payment successful! Reference: " + txn.getReferenceNumber());
                            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));

                            String verifiedMerchantName = preview.getMerchant().getName();
                            String receiptText = ReceiptBuilder.forMerchantPayment(
                                    txn, verifiedMerchantName, preview.getQrReference(), false).build();
                            txtReceipt.setText(receiptText);
                            txtReceipt.setCaretPosition(0);

                            if (paymentCompletedCallback != null) {
                                paymentCompletedCallback.run();
                            }
                            future.complete(txn);
                        } catch (Exception ex) {
                            Throwable cause = unwrapCause(ex);
                            btnConfirmPay.setEnabled(loadedPayload != null);
                            lblPayFeedback.setText("Payment failed: " + extractErrorMessage(cause));
                            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                            if (onPaymentError != null) {
                                onPaymentError.accept(cause);
                            }
                            future.completeExceptionally(cause);
                        }
                    }
                }.execute();
            }
        }.execute();

        return future;
    }

    public void resetPayForm() {
        loadedPayload = null;
        verifiedMerchant = null;
        lblImportedFile.setText("No QR file loaded.");
        lblQrThumbnail.setIcon(null);
        lblQrThumbnail.setText("[ QR Image ]");
        txtConfirmation.setText("Import a merchant QR code image to confirm recipient and view details.");
        txtPayAmount.setText("");
        txtPayAmount.setEditable(true);
        txtPayPin.setText("");
        btnConfirmPay.setEnabled(false);
        lblPayFeedback.setText(" ");
        txtReceipt.setText("");
    }

    // --- Merchant Generator Actions ---

    private void handleGenerateQrAction() {
        if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
            return;
        }
        String rawAmount = txtGenAmount.getText() != null ? txtGenAmount.getText().trim() : "";
        String note = txtGenNote.getText() != null ? txtGenNote.getText().trim() : "";
        generateQrAsync(rawAmount, note);
    }

    public CompletableFuture<MerchantQrPayload> generateQrAsync(String rawAmountText, String noteText) {
        CompletableFuture<MerchantQrPayload> future = new CompletableFuture<>();
        if (qrService == null) {
            IllegalStateException err = new IllegalStateException("MerchantQrService is not initialized.");
            runOnEdt(() -> {
                lblMerchantFeedback.setText(err.getMessage());
                lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }

        SessionManager session = SessionManager.getInstance();
        User currentUser = session.getCurrentUser();
        Wallet currentWallet = session.getCurrentWallet();
        if (currentUser != null && currentUser.getRole() != UserRole.MERCHANT && !currentUser.isAdmin()) {
            SecurityException err = new SecurityException("Only merchant or administrator accounts can generate merchant QR codes.");
            runOnEdt(() -> {
                lblMerchantFeedback.setText(err.getMessage());
                lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(err);
            return future;
        }

        OtogConfig config = OtogConfig.getInstance();
        OtogConfig.MerchantSeedConfig seedConfig = config.getMerchantSeedConfig();
        final String merchantId;
        final String merchantWalletId;
        final String merchantName;
        if (currentUser != null && currentUser.getRole() == UserRole.MERCHANT && currentWallet != null) {
            merchantId = currentUser.getUserId();
            merchantWalletId = currentWallet.getWalletId();
            merchantName = currentUser.getName();
        } else {
            merchantId = seedConfig.userId();
            merchantWalletId = seedConfig.walletId();
            merchantName = seedConfig.username();
        }

        String rawAmount = rawAmountText != null ? rawAmountText.trim() : "";
        BigDecimal parsedAmount = null;
        if (!rawAmount.isEmpty()) {
            try {
                parsedAmount = new BigDecimal(rawAmount);
            } catch (NumberFormatException ex) {
                IllegalArgumentException err = new IllegalArgumentException(
                        "Invalid amount format. Please enter a positive number or leave blank.", ex);
                runOnEdt(() -> {
                    lblMerchantFeedback.setText(err.getMessage());
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    txtGenAmount.requestFocusInWindow();
                });
                future.completeExceptionally(err);
                return future;
            }
        }

        final BigDecimal amount = parsedAmount;
        final String note = noteText != null ? noteText.trim() : "";
        final String ref = IdGenerator.nextReferenceNumber();
        final String currency = config.getDefaultCurrency();

        runOnEdt(() -> {
            btnGenerateQr.setEnabled(false);
            btnSaveQr.setEnabled(false);
            lblMerchantFeedback.setText("Generating merchant QR image...");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        });

        new SwingWorker<GeneratedQrOutcome, Void>() {
            @Override
            protected GeneratedQrOutcome doInBackground() {
                recordOffEdt();
                SessionManager.getInstance().touchActivity();
                MerchantQrPayload payload = new MerchantQrPayload(
                        merchantId,
                        merchantWalletId,
                        merchantName,
                        amount,
                        currency,
                        ref,
                        note
                );
                BufferedImage qrImage = qrService.generateQrImage(payload);
                Image scaled = qrImage.getScaledInstance(160, 160, Image.SCALE_SMOOTH);
                BufferedImage previewImg = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = previewImg.createGraphics();
                g.drawImage(scaled, 0, 0, null);
                g.dispose();
                return new GeneratedQrOutcome(payload, qrImage, previewImg);
            }

            @Override
            protected void done() {
                btnGenerateQr.setEnabled(true);
                try {
                    GeneratedQrOutcome outcome = get();
                    generatedPayload = outcome.payload();
                    lastGeneratedImage = outcome.fullImage();
                    lblGeneratedQr.setIcon(new ImageIcon(outcome.previewImage()));
                    lblGeneratedQr.setText("");
                    btnSaveQr.setEnabled(true);
                    lblMerchantFeedback.setText("QR Code generated for " + merchantName + " (Ref: " + ref
                            + ")! Click 'Export / Save QR Image...' to export.");
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    future.complete(outcome.payload());
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    btnSaveQr.setEnabled(lastGeneratedImage != null);
                    lblMerchantFeedback.setText("Failed to generate QR: " + extractErrorMessage(cause));
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    future.completeExceptionally(cause);
                }
            }
        }.execute();

        return future;
    }

    private void handleSaveQrAction() {
        if (beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) {
            return;
        }
        if (qrService == null) {
            lblMerchantFeedback.setText("MerchantQrService is not initialized.");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }
        if (generatedPayload == null || lastGeneratedImage == null) {
            lblMerchantFeedback.setText("Please generate a QR code first.");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export / Save Merchant QR Code Image");
        chooser.setSelectedFile(new File("OTOG_QR_" + generatedPayload.getQrReference() + ".png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png", "PNG"));

        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }
        exportGeneratedQrAsync(target.toPath());
    }

    public CompletableFuture<Path> exportGeneratedQrAsync(Path targetFile) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        if (qrService == null) {
            IllegalStateException err = new IllegalStateException("MerchantQrService is not initialized.");
            future.completeExceptionally(err);
            return future;
        }
        if (generatedPayload == null || lastGeneratedImage == null) {
            IllegalStateException err = new IllegalStateException("Please generate a QR code first.");
            future.completeExceptionally(err);
            return future;
        }

        final BufferedImage imageToExport = lastGeneratedImage;
        runOnEdt(() -> {
            btnSaveQr.setEnabled(false);
            lblMerchantFeedback.setText("Exporting QR image...");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        });

        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() {
                recordOffEdt();
                SessionManager.getInstance().touchActivity();
                return qrService.exportQrImage(imageToExport, targetFile);
            }

            @Override
            protected void done() {
                btnSaveQr.setEnabled(lastGeneratedImage != null);
                try {
                    Path saved = get();
                    lblMerchantFeedback.setText("Successfully saved QR image to: " + saved.toAbsolutePath());
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    future.complete(saved);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    lblMerchantFeedback.setText("Failed to export image: " + extractErrorMessage(cause));
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    future.completeExceptionally(cause);
                }
            }
        }.execute();

        return future;
    }

    public void refreshMerchantSalesIfAuthorized() {
        User currentUser = SessionManager.getInstance().getCurrentUser();
        if (currentUser != null && (currentUser.getRole() == UserRole.MERCHANT || currentUser.isAdmin())) {
            refreshMerchantSalesAsync();
        }
    }

    public CompletableFuture<List<Transaction>> refreshMerchantSalesAsync() {
        CompletableFuture<List<Transaction>> future = new CompletableFuture<>();
        if (walletService == null) {
            future.complete(Collections.emptyList());
            return future;
        }

        SessionManager session = SessionManager.getInstance();
        User currentUser = session.getCurrentUser();
        Wallet currentWallet = session.getCurrentWallet();

        // Enforce CAP-09: standard USER accounts must never inspect merchant sales history
        if (currentUser == null || (currentUser.getRole() != UserRole.MERCHANT && !currentUser.isAdmin())) {
            SecurityException denied = new SecurityException(
                    "Access denied: Merchant sales history is restricted to merchant or admin accounts.");
            runOnEdt(() -> {
                loadedMerchantSales = Collections.emptyList();
                salesTableModel.setRowCount(0);
                lblMerchantFeedback.setText(denied.getMessage());
                lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            });
            future.completeExceptionally(denied);
            return future;
        }

        final String merchantWalletId;
        if (currentUser.getRole() == UserRole.MERCHANT && currentWallet != null) {
            merchantWalletId = currentWallet.getWalletId();
        } else {
            merchantWalletId = OtogConfig.getInstance().getMerchantSeedConfig().walletId();
        }

        runOnEdt(() -> {
            btnRefreshSales.setEnabled(false);
            lblMerchantFeedback.setText("Loading received QR payments for "
                    + ReceiptBuilder.maskIdentifier(merchantWalletId) + "...");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        });

        SwingWorker<List<Transaction>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Transaction> doInBackground() {
                recordOffEdt();
                SessionManager.getInstance().touchActivity();
                int limit = OtogConfig.getInstance().getRecentTransactionLimit();
                List<Transaction> all = walletService.getRecentTransactions(merchantWalletId, limit);
                List<Transaction> qrSales = new ArrayList<>();
                for (Transaction t : all) {
                    if (isReceivedMerchantQrPayment(merchantWalletId, t)) {
                        qrSales.add(t);
                    }
                }
                return qrSales;
            }

            @Override
            protected void done() {
                btnRefreshSales.setEnabled(true);
                try {
                    loadedMerchantSales = get();
                    salesTableModel.setRowCount(0);
                    DateTimeFormatter dtf = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());
                    for (Transaction t : loadedMerchantSales) {
                        salesTableModel.addRow(new Object[]{
                                t.getTransactionId(),
                                t.getReferenceNumber(),
                                t.getCreatedAt() != null ? t.getCreatedAt().format(dtf) : "N/A",
                                ReceiptBuilder.maskIdentifier(t.getSourceWalletId()),
                                MoneyUtil.format(t.getAmount(), t.getCurrency()),
                                t.getStatus()
                        });
                    }
                    lblMerchantFeedback.setText("Loaded " + loadedMerchantSales.size() + " received QR payment(s).");
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    future.complete(loadedMerchantSales);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    lblMerchantFeedback.setText("Failed to load sales: " + extractErrorMessage(cause));
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    if (onPaymentError != null) {
                        onPaymentError.accept(cause);
                    }
                    future.completeExceptionally(cause);
                }
            }
        };
        worker.execute();
        return future;
    }

    private static boolean isReceivedMerchantQrPayment(String merchantWalletId, Transaction txn) {
        if (txn == null || !merchantWalletId.equals(txn.getTargetWalletId())) {
            return false;
        }
        boolean hasQrRef = txn.getReferenceNumber() != null
                && txn.getReferenceNumber().startsWith(MerchantQrService.QR_TRANSFER_REF_PREFIX);
        boolean hasQrDesc = txn.getDescription() != null && txn.getDescription().startsWith("QR Pay:");
        return hasQrRef || hasQrDesc;
    }

    public String buildSelectedSaleReceiptText() {
        int viewRow = tblMerchantSales.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = tblMerchantSales.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= loadedMerchantSales.size()) {
            return null;
        }
        Transaction txn = loadedMerchantSales.get(modelRow);
        User currentUser = SessionManager.getInstance().getCurrentUser();
        String merchantName = (currentUser != null && currentUser.getRole() == UserRole.MERCHANT
                && currentUser.getName() != null && !currentUser.getName().isBlank())
                ? currentUser.getName().trim()
                : OtogConfig.getInstance().getMerchantSeedConfig().username();
        String ref = txn.getReferenceNumber();
        String qrRef = (ref != null && ref.startsWith(MerchantQrService.QR_TRANSFER_REF_PREFIX))
                ? ref.substring(MerchantQrService.QR_TRANSFER_REF_PREFIX.length())
                : ref;
        return ReceiptBuilder.forMerchantPayment(txn, merchantName, qrRef, true).build();
    }

    private void showSelectedSaleReceipt() {
        String receipt = buildSelectedSaleReceiptText();
        if (receipt == null) {
            return;
        }
        JTextArea area = new JTextArea(receipt);
        area.setEditable(false);
        area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        area.setMargin(new Insets(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(OtogConfig.getInstance().getUiDimension(
                "ui.receipt.width", "ui.receipt.height", 460, 360));
        JOptionPane.showMessageDialog(this, scroll, "Merchant Payment Receipt", JOptionPane.INFORMATION_MESSAGE);
    }

    private void recordOffEdt() {
        boolean offEdt = !SwingUtilities.isEventDispatchThread();
        lastOperationExecutedOffEdt = offEdt;
        if (offEdtObserver != null) {
            offEdtObserver.accept(offEdt);
        }
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static Throwable unwrapCause(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String extractErrorMessage(Throwable ex) {
        if (ex == null) {
            return "Unknown error";
        }
        String msg = ex.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : ex.getClass().getSimpleName();
    }

    private record ImportOutcome(MerchantQrPayload payload, User merchant, BufferedImage thumbnail) {
    }

    private record GeneratedQrOutcome(MerchantQrPayload payload, BufferedImage fullImage, BufferedImage previewImage) {
    }

    // --- Getters for Tests & Shell ---

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTabbedPane getTabs() {
        return tabsQr;
    }

    public JButton getImportQrButton() {
        return btnImportQr;
    }

    public JLabel getImportedFileLabel() {
        return lblImportedFile;
    }

    public JLabel getQrThumbnailLabel() {
        return lblQrThumbnail;
    }

    public JTextArea getConfirmationArea() {
        return txtConfirmation;
    }

    public JTextField getPayAmountField() {
        return txtPayAmount;
    }

    public JPasswordField getPayPinField() {
        return txtPayPin;
    }

    public JButton getConfirmPayButton() {
        return btnConfirmPay;
    }

    public JButton getClearPayButton() {
        return btnClearPay;
    }

    public JButton getBackToOverviewButton() {
        return btnBackToOverview;
    }

    public JLabel getPayFeedbackLabel() {
        return lblPayFeedback;
    }

    public JTextArea getReceiptArea() {
        return txtReceipt;
    }

    public JTextField getGenAmountField() {
        return txtGenAmount;
    }

    public JTextField getGenNoteField() {
        return txtGenNote;
    }

    public JButton getGenerateQrButton() {
        return btnGenerateQr;
    }

    public JButton getSaveQrButton() {
        return btnSaveQr;
    }

    public JLabel getGeneratedQrLabel() {
        return lblGeneratedQr;
    }

    public JTable getMerchantSalesTable() {
        return tblMerchantSales;
    }

    public DefaultTableModel getSalesTableModel() {
        return salesTableModel;
    }

    public JButton getViewMerchantReceiptButton() {
        return btnViewMerchantReceipt;
    }

    public JButton getRefreshSalesButton() {
        return btnRefreshSales;
    }

    public JLabel getMerchantFeedbackLabel() {
        return lblMerchantFeedback;
    }

    public MerchantQrPayload getLoadedPayload() {
        return loadedPayload;
    }

    public User getVerifiedMerchant() {
        return verifiedMerchant;
    }

    public MerchantQrPayload getGeneratedPayload() {
        return generatedPayload;
    }

    // --- Manual UI Construction Fallback ---

    private void $$$setupUI$$$() {
        OtogConfig config = OtogConfig.getInstance();
        String currency = config.getDefaultCurrency();

        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(1, 1, new Insets(10, 10, 10, 10), 10, 10));

        tabsQr = new JTabbedPane();
        pnlRoot.add(tabsQr, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(860, 540), null, 0, false));

        // Pay Tab
        pnlPayTab = new JPanel();
        pnlPayTab.setLayout(new GridLayoutManager(7, 2, new Insets(12, 12, 12, 12), 10, 8));
        tabsQr.addTab("Pay Merchant (QR Import)", pnlPayTab);

        JLabel lblImportPrompt = new JLabel("Select QR Code Image:");
        pnlPayTab.add(lblImportPrompt, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        JPanel pnlImportActions = new JPanel(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), 8, 0));
        btnImportQr = new JButton("Import QR Image (.png)...");
        lblImportedFile = new JLabel("No QR file loaded.");
        pnlImportActions.add(btnImportQr, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlImportActions.add(lblImportedFile, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        pnlPayTab.add(pnlImportActions, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        lblQrThumbnail = new JLabel("[ QR Image ]", SwingConstants.CENTER);
        lblQrThumbnail.setPreferredSize(new Dimension(120, 120));
        lblQrThumbnail.setBorder(BorderFactory.createLineBorder(UiTheme.color(UiTheme.ColorRole.BORDER)));
        pnlPayTab.add(lblQrThumbnail, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(120, 120), null, 0, false));

        txtConfirmation = new JTextArea("Import a merchant QR code image to confirm recipient and view details.");
        txtConfirmation.setEditable(false);
        txtConfirmation.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        txtConfirmation.setLineWrap(true);
        txtConfirmation.setWrapStyleWord(true);
        JScrollPane scrollConfirm = new JScrollPane(txtConfirmation);
        scrollConfirm.setBorder(UiTheme.titledBorder("Merchant Recipient Confirmation"));
        pnlPayTab.add(scrollConfirm, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(450, 110), null, 0, false));

        lblPayAmount = new JLabel("Payment Amount (" + currency + "):");
        pnlPayTab.add(lblPayAmount, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtPayAmount = new JTextField();
        pnlPayTab.add(txtPayAmount, new GridConstraints(2, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        JLabel lblPin = new JLabel("Transaction PIN:");
        pnlPayTab.add(lblPin, new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtPayPin = new JPasswordField();
        pnlPayTab.add(txtPayPin, new GridConstraints(3, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        JPanel pnlPayBtns = new JPanel(new GridLayoutManager(1, 4, new Insets(4, 0, 4, 0), 8, 0));
        btnConfirmPay = new JButton("Confirm & Pay");
        btnConfirmPay.setEnabled(false);
        btnClearPay = new JButton("Clear");
        btnBackToOverview = new JButton("\u2190 Back to Overview");
        pnlPayBtns.add(btnConfirmPay, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlPayBtns.add(btnClearPay, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlPayBtns.add(btnBackToOverview, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlPayBtns.add(new Spacer(), new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        pnlPayTab.add(pnlPayBtns, new GridConstraints(4, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        lblPayFeedback = new JLabel(" ");
        pnlPayTab.add(lblPayFeedback, new GridConstraints(5, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        txtReceipt = new JTextArea();
        txtReceipt.setEditable(false);
        txtReceipt.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        txtReceipt.setLineWrap(true);
        txtReceipt.setWrapStyleWord(true);
        JScrollPane scrollReceipt = new JScrollPane(txtReceipt);
        scrollReceipt.setBorder(UiTheme.titledBorder("Payment Receipt"));
        pnlPayTab.add(scrollReceipt, new GridConstraints(6, 0, 1, 2,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(700, 130), null, 0, false));

        // Merchant Terminal Tab
        pnlMerchantTab = new JPanel();
        pnlMerchantTab.setLayout(new GridLayoutManager(6, 2, new Insets(12, 12, 12, 12), 10, 8));
        tabsQr.addTab(MERCHANT_TAB_TITLE, pnlMerchantTab);

        lblGenAmount = new JLabel("Amount (" + currency + ", Optional):");
        pnlMerchantTab.add(lblGenAmount, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtGenAmount = new JTextField();
        pnlMerchantTab.add(txtGenAmount, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        JLabel lblGenNote = new JLabel("Order / Note (Optional):");
        pnlMerchantTab.add(lblGenNote, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtGenNote = new JTextField();
        pnlMerchantTab.add(txtGenNote, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        JPanel pnlGenBtns = new JPanel(new GridLayoutManager(1, 3, new Insets(2, 0, 2, 0), 8, 0));
        btnGenerateQr = new JButton("Generate Merchant QR");
        btnSaveQr = new JButton("Export / Save QR Image...");
        btnSaveQr.setEnabled(false);
        pnlGenBtns.add(btnGenerateQr, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlGenBtns.add(btnSaveQr, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlGenBtns.add(new Spacer(), new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        pnlMerchantTab.add(pnlGenBtns, new GridConstraints(2, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        lblGeneratedQr = new JLabel("[ Generated QR Preview ]", SwingConstants.CENTER);
        lblGeneratedQr.setPreferredSize(new Dimension(160, 160));
        lblGeneratedQr.setBorder(BorderFactory.createLineBorder(UiTheme.color(UiTheme.ColorRole.BORDER)));
        pnlMerchantTab.add(lblGeneratedQr, new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(160, 160), null, 0, false));

        tblMerchantSales = new JTable();
        JScrollPane scrollSales = new JScrollPane(tblMerchantSales);
        scrollSales.setBorder(UiTheme.titledBorder("Received Merchant Payments"));
        pnlMerchantTab.add(scrollSales, new GridConstraints(3, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(520, 160), null, 0, false));

        JPanel pnlSalesBtns = new JPanel(new GridLayoutManager(1, 3, new Insets(4, 0, 0, 0), 8, 0));
        btnViewMerchantReceipt = new JButton("View Merchant Receipt");
        btnViewMerchantReceipt.setEnabled(false);
        btnRefreshSales = new JButton("Refresh Sales");
        pnlSalesBtns.add(btnViewMerchantReceipt, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlSalesBtns.add(btnRefreshSales, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlSalesBtns.add(new Spacer(), new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        pnlMerchantTab.add(pnlSalesBtns, new GridConstraints(4, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));

        lblMerchantFeedback = new JLabel(" ");
        pnlMerchantTab.add(lblMerchantFeedback, new GridConstraints(5, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
