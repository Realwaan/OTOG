package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.exception.MalformedQrException;
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
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Bound IntelliJ Swing form panel for Merchant QR Payments (CAP-11):
 * - Customer Flow: Import QR image, confirm recipient & amount, authorize payment with PIN, view receipt.
 * - Merchant Flow: Generate project QR code, export image, view received payments and merchant receipts.
 */
public class MerchantQrPanel extends JPanel {

    private static final String[] SALES_COLUMNS = {"Txn ID", "Reference", "Date", "Customer", "Amount", "Status"};

    private final MerchantQrService qrService;
    private final WalletOperations walletService;
    private final AuthService authService;
    private final Runnable backToOverviewCallback;
    private final Runnable paymentCompletedCallback;
    private Consumer<Throwable> onPaymentError;
    private volatile boolean lastPaymentExecutedOffEdt;

    private MerchantQrPayload loadedPayload;
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
    private JTextField txtPayAmount;
    private JPasswordField txtPayPin;
    private JButton btnConfirmPay;
    private JButton btnClearPay;
    private JButton btnBackToOverview;
    private JLabel lblPayFeedback;
    private JTextArea txtReceipt;

    // Merchant Generator Tab
    private JPanel pnlMerchantTab;
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
        this.authService = authService;
        this.backToOverviewCallback = backToOverviewCallback;
        this.paymentCompletedCallback = paymentCompletedCallback;

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureSalesTableModelInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        initActions();
    }

    private void ensureSalesTableModelInitialized() {
        if (salesTableModel == null) {
            salesTableModel = new DefaultTableModel(SALES_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblMerchantSales != null && tblMerchantSales.getModel() != salesTableModel) {
            tblMerchantSales.setModel(salesTableModel);
            tblMerchantSales.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }
    }

    public void setOnPaymentError(Consumer<Throwable> onPaymentError) {
        this.onPaymentError = onPaymentError;
    }

    public boolean isLastPaymentExecutedOffEdt() {
        return lastPaymentExecutedOffEdt;
    }

    private void initActions() {
        if (btnImportQr != null) {
            btnImportQr.addActionListener(e -> handleImportQrAction());
        }
        if (btnConfirmPay != null) {
            btnConfirmPay.addActionListener(e -> handleConfirmPayAction());
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
        if (btnSaveQr != null) {
            btnSaveQr.addActionListener(e -> handleSaveQrAction());
        }
        if (btnRefreshSales != null) {
            btnRefreshSales.addActionListener(e -> refreshMerchantSalesAsync());
        }
        if (btnViewMerchantReceipt != null) {
            btnViewMerchantReceipt.addActionListener(e -> showSelectedSaleReceipt());
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
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select OTOG Merchant QR Image");
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png", "PNG"));

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        lblImportedFile.setText(file.getName());
        lblPayFeedback.setText("Reading and verifying QR code image...");
        lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));

        if (qrService == null) {
            lblPayFeedback.setText("MerchantQrService is not initialized.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }

        SwingWorker<MerchantQrPayload, Void> worker = new SwingWorker<>() {
            private BufferedImage thumbnail;

            @Override
            protected MerchantQrPayload doInBackground() throws Exception {
                MerchantQrPayload payload = qrService.importQrImage(file.toPath());
                BufferedImage img = ImageIO.read(file);
                if (img != null) {
                    Image scaled = img.getScaledInstance(120, 120, Image.SCALE_SMOOTH);
                    thumbnail = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = thumbnail.createGraphics();
                    g.drawImage(scaled, 0, 0, null);
                    g.dispose();
                }
                return payload;
            }

            @Override
            protected void done() {
                try {
                    MerchantQrPayload payload = get();
                    loadedPayload = payload;
                    if (thumbnail != null) {
                        lblQrThumbnail.setIcon(new ImageIcon(thumbnail));
                        lblQrThumbnail.setText("");
                    }

                    // Format recipient confirmation preview
                    StringBuilder sb = new StringBuilder();
                    sb.append("Merchant:  ").append(payload.getMerchantName()).append("\n");
                    sb.append("ID:        ").append(payload.getMerchantId()).append("\n");
                    sb.append("Terminal:  ").append(ReceiptBuilder.maskIdentifier(payload.getMerchantWalletId())).append("\n");
                    sb.append("Currency:  ").append(payload.getCurrency()).append("\n");
                    sb.append("Reference: ").append(payload.getQrReference()).append("\n");
                    if (payload.hasFixedAmount()) {
                        sb.append("Amount:    ").append(MoneyUtil.format(payload.getAmount(), payload.getCurrency())).append(" (Fixed)\n");
                        txtPayAmount.setText(payload.getAmount().toPlainString());
                        txtPayAmount.setEditable(false);
                    } else {
                        sb.append("Amount:    Open (Customer specifies amount)\n");
                        txtPayAmount.setText("");
                        txtPayAmount.setEditable(true);
                    }
                    if (payload.getNote() != null && !payload.getNote().isEmpty()) {
                        sb.append("Note:      ").append(payload.getNote()).append("\n");
                    }
                    txtConfirmation.setText(sb.toString());
                    txtConfirmation.setCaretPosition(0);

                    btnConfirmPay.setEnabled(true);
                    lblPayFeedback.setText("QR Code verified! Review recipient details, enter PIN, and click Confirm & Pay.");
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                    if (!payload.hasFixedAmount()) {
                        txtPayAmount.requestFocusInWindow();
                    } else {
                        txtPayPin.requestFocusInWindow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    loadedPayload = null;
                    btnConfirmPay.setEnabled(false);
                    lblQrThumbnail.setIcon(null);
                    lblQrThumbnail.setText("[ Invalid QR ]");
                    txtConfirmation.setText("Failed to import QR: " + cause.getMessage());
                    lblPayFeedback.setText("Error: " + cause.getMessage());
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                }
            }
        };
        worker.execute();
    }

    private void handleConfirmPayAction() {
        if (loadedPayload == null) {
            lblPayFeedback.setText("Please import a valid Merchant QR code image first.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }

        SessionManager session = SessionManager.getInstance();
        User currentUser = session.getCurrentUser();
        if (currentUser == null) {
            lblPayFeedback.setText("Active authenticated user session is required.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            return;
        }

        String rawAmount = txtPayAmount.getText() != null ? txtPayAmount.getText().trim() : "";
        BigDecimal inputAmount;
        try {
            inputAmount = rawAmount.isEmpty() ? null : new BigDecimal(rawAmount);
        } catch (NumberFormatException nfe) {
            lblPayFeedback.setText("Invalid amount format. Please enter a valid number (e.g. 150.00).");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            txtPayAmount.requestFocusInWindow();
            return;
        }

        char[] pinChars = txtPayPin.getPassword();
        String rawPin = (pinChars != null && pinChars.length > 0) ? new String(pinChars) : "";
        if (rawPin.isEmpty()) {
            lblPayFeedback.setText("Transaction PIN is required to authorize payment.");
            lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
            txtPayPin.requestFocusInWindow();
            return;
        }

        btnConfirmPay.setEnabled(false);
        lblPayFeedback.setText("Processing merchant payment...");
        lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));

        MerchantQrPayload payload = loadedPayload;
        SwingWorker<Transaction, Void> worker = new SwingWorker<>() {
            @Override
            protected Transaction doInBackground() {
                lastPaymentExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                MerchantPaymentPreview preview = qrService.previewPayment(currentUser.getUserId(), payload, inputAmount);
                return qrService.confirmAndPay(preview, rawPin);
            }

            @Override
            protected void done() {
                txtPayPin.setText("");
                btnConfirmPay.setEnabled(true);
                try {
                    Transaction txn = get();
                    lblPayFeedback.setText("Payment successful! Reference: " + txn.getReferenceNumber());
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));

                    String receiptText = ReceiptBuilder.forMerchantPayment(
                            txn, payload.getMerchantName(), payload.getQrReference(), false).build();
                    txtReceipt.setText(receiptText);
                    txtReceipt.setCaretPosition(0);

                    if (paymentCompletedCallback != null) {
                        paymentCompletedCallback.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    lblPayFeedback.setText("Payment failed: " + cause.getMessage());
                    lblPayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    if (onPaymentError != null) {
                        onPaymentError.accept(cause);
                    }
                }
            }
        };
        worker.execute();
    }

    public void resetPayForm() {
        loadedPayload = null;
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
        SessionManager session = SessionManager.getInstance();
        User currentUser = session.getCurrentUser();
        Wallet currentWallet = session.getCurrentWallet();

        String merchantId;
        String merchantWalletId;
        String merchantName;

        OtogConfig config = OtogConfig.getInstance();
        OtogConfig.MerchantSeedConfig seedConfig = config.getMerchantSeedConfig();

        if (currentUser != null && currentUser.getRole() == UserRole.MERCHANT && currentWallet != null) {
            merchantId = currentUser.getUserId();
            merchantWalletId = currentWallet.getWalletId();
            merchantName = currentUser.getName();
        } else {
            // Default to configured seeded merchant profile
            merchantId = seedConfig.userId();
            merchantWalletId = seedConfig.walletId();
            merchantName = seedConfig.username();
        }

        String rawAmount = txtGenAmount.getText() != null ? txtGenAmount.getText().trim() : "";
        BigDecimal amount = null;
        if (!rawAmount.isEmpty()) {
            try {
                amount = new BigDecimal(rawAmount);
            } catch (NumberFormatException ex) {
                lblMerchantFeedback.setText("Invalid amount format. Please enter a positive number or leave blank.");
                lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                txtGenAmount.requestFocusInWindow();
                return;
            }
        }

        String note = txtGenNote.getText() != null ? txtGenNote.getText().trim() : "";
        String ref = IdGenerator.nextReferenceNumber();

        try {
            MerchantQrPayload payload = new MerchantQrPayload(
                    merchantId,
                    merchantWalletId,
                    merchantName,
                    amount,
                    MerchantQrPayload.DEFAULT_CURRENCY,
                    ref,
                    note
            );

            BufferedImage qrImage = qrService.generateQrImage(payload);
            generatedPayload = payload;
            lastGeneratedImage = qrImage;

            Image scaled = qrImage.getScaledInstance(160, 160, Image.SCALE_SMOOTH);
            lblGeneratedQr.setIcon(new ImageIcon(scaled));
            lblGeneratedQr.setText("");
            btnSaveQr.setEnabled(true);

            lblMerchantFeedback.setText("QR Code generated for " + merchantName + " (Ref: " + ref + ")! Click 'Export / Save QR Image...' to export.");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
        } catch (Exception ex) {
            lblMerchantFeedback.setText("Failed to generate QR: " + ex.getMessage());
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
        }
    }

    private void handleSaveQrAction() {
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
        if (!target.getName().toLowerCase().endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }

        try {
            qrService.exportQrImage(generatedPayload, target.toPath());
            lblMerchantFeedback.setText("Successfully saved QR image to: " + target.getAbsolutePath());
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
        } catch (Exception ex) {
            lblMerchantFeedback.setText("Failed to export image: " + ex.getMessage());
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
        }
    }

    public void refreshMerchantSalesAsync() {
        if (walletService == null) {
            return;
        }
        ensureSalesTableModelInitialized();

        SessionManager session = SessionManager.getInstance();
        User currentUser = session.getCurrentUser();
        Wallet currentWallet = session.getCurrentWallet();

        if (currentUser != null && currentUser.getRole() != UserRole.MERCHANT && !currentUser.isAdmin()) {
            loadedMerchantSales = Collections.emptyList();
            salesTableModel.setRowCount(0);
            btnViewMerchantReceipt.setEnabled(false);
            lblMerchantFeedback.setText("Sign in as a Merchant or Administrator to inspect received merchant sales.");
            lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            return;
        }

        String merchantWalletId;
        if (currentUser != null && currentUser.getRole() == UserRole.MERCHANT && currentWallet != null) {
            merchantWalletId = currentWallet.getWalletId();
        } else {
            merchantWalletId = OtogConfig.getInstance().getMerchantSeedConfig().walletId();
        }

        lblMerchantFeedback.setText("Loading received payments for " + merchantWalletId + "...");
        lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));

        SwingWorker<List<Transaction>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Transaction> doInBackground() {
                List<Transaction> all = walletService.getRecentTransactions(
                        merchantWalletId, OtogConfig.getInstance().getRecentTransactionLimit());
                List<Transaction> incoming = new ArrayList<>();
                for (Transaction t : all) {
                    if (merchantWalletId.equals(t.getTargetWalletId())) {
                        incoming.add(t);
                    }
                }
                return incoming;
            }

            @Override
            protected void done() {
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
                    lblMerchantFeedback.setText("Loaded " + loadedMerchantSales.size() + " incoming payment(s).");
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                } catch (Exception ex) {
                    Throwable cause = (ex instanceof ExecutionException && ex.getCause() != null)
                            ? ex.getCause() : ex;
                    lblMerchantFeedback.setText("Failed to load sales: " + cause.getMessage());
                    lblMerchantFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                }
            }
        };
        worker.execute();
    }

    private void showSelectedSaleReceipt() {
        int selectedRow = tblMerchantSales.getSelectedRow();
        if (selectedRow < 0 || selectedRow >= loadedMerchantSales.size()) {
            return;
        }
        Transaction txn = loadedMerchantSales.get(selectedRow);
        User currentUser = SessionManager.getInstance().getCurrentUser();
        String merchantName = (currentUser != null && currentUser.getRole() == UserRole.MERCHANT
                && currentUser.getName() != null && !currentUser.getName().trim().isEmpty())
                ? currentUser.getName().trim()
                : OtogConfig.getInstance().getMerchantSeedConfig().username();
        String receipt = ReceiptBuilder.forMerchantPayment(txn, merchantName, txn.getReferenceNumber(), true).build();

        JTextArea area = new JTextArea(receipt);
        area.setEditable(false);
        area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(500, 360));
        JOptionPane.showMessageDialog(this, scroll, "Merchant Payment Receipt", JOptionPane.INFORMATION_MESSAGE);
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

    public MerchantQrPayload getGeneratedPayload() {
        return generatedPayload;
    }

    // --- Manual UI Construction Fallback ---

    private void $$$setupUI$$$() {
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
        lblQrThumbnail.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        pnlPayTab.add(lblQrThumbnail, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(120, 120), null, 0, false));

        txtConfirmation = new JTextArea("Import a merchant QR code image to confirm recipient and view details.");
        txtConfirmation.setEditable(false);
        txtConfirmation.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txtConfirmation.setLineWrap(true);
        txtConfirmation.setWrapStyleWord(true);
        JScrollPane scrollConfirm = new JScrollPane(txtConfirmation);
        scrollConfirm.setBorder(UiTheme.titledBorder("Merchant Recipient Confirmation"));
        pnlPayTab.add(scrollConfirm, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(450, 110), null, 0, false));

        JLabel lblAmount = new JLabel("Payment Amount (PHP):");
        pnlPayTab.add(lblAmount, new GridConstraints(2, 0, 1, 1,
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
        btnBackToOverview = new JButton("← Back to Overview");
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
        txtReceipt.setFont(new Font("Monospaced", Font.PLAIN, 12));
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
        tabsQr.addTab("Merchant Terminal & Sales", pnlMerchantTab);

        JLabel lblGenAmount = new JLabel("Amount (PHP, Optional):");
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
        lblGeneratedQr.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        pnlMerchantTab.add(lblGeneratedQr, new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(160, 160), null, 0, false));

        salesTableModel = new DefaultTableModel(SALES_COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblMerchantSales = new JTable(salesTableModel);
        tblMerchantSales.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
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
