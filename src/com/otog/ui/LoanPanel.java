package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.model.LoanStatus;
import com.otog.model.LoanTransaction;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.LoanService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bound IntelliJ Swing form panel for CAP-12 Basic Simulated Loan Workflow.
 * Supports configured single-product repayment preview, loan application,
 * full lump-sum repayment with PIN authorization, and role-gated admin approval/rejection.
 */
public class LoanPanel extends JPanel {

    private static final String ADMIN_TAB_TITLE = "Admin Loan Approvals";
    private static final String[] USER_LOAN_COLUMNS = {
            "Loan ID", "Reference", "Principal", "Interest", "Fee", "Total Due", "Amount Due", "Status", "Due Date", "Decision"
    };
    private static final String[] ADMIN_LOAN_COLUMNS = {
            "Loan ID", "Reference", "Borrower", "Wallet", "Principal", "Total Due", "Amount Due", "Status", "Due Date", "Reason"
    };

    private final WalletOperations walletService;
    private final LoanService loanService;
    private final AuthService authService;
    private final AtomicBoolean operationInFlight = new AtomicBoolean(false);

    private JPanel pnlRoot;
    private JTabbedPane tabsLoan;

    private JPanel pnlBorrowerTab;
    private JPanel pnlProductSummary;
    private JLabel lblProductName;
    private JLabel lblProductTerms;
    private JLabel lblFormulaSummary;
    private JLabel lblOutstandingDue;
    private JLabel lblScopeNotice;

    private JPanel pnlApplicationForm;
    private JLabel lblPrincipalCaption;
    private JTextField txtPrincipal;
    private JLabel lblPreviewSummary;
    private JPanel pnlApplyActions;
    private JButton btnPreviewLoan;
    private JButton btnApplyLoan;
    private JButton btnBackToOverview;
    private JLabel lblLoanFeedback;

    private JPanel pnlUserLoans;
    private JScrollPane scrollUserLoans;
    private JTable tblUserLoans;
    private JPanel pnlRepayControls;
    private JLabel lblSelectedLoanDue;
    private JPasswordField txtRepayPin;
    private JButton btnRepayLoan;
    private JLabel lblRepayFeedback;

    private JPanel pnlAdminTab;
    private JScrollPane scrollAdminLoans;
    private JTable tblAdminLoans;
    private JPanel pnlAdminDecision;
    private JTextField txtAdminReason;
    private JPanel pnlAdminActions;
    private JButton btnAdminApprove;
    private JButton btnAdminReject;
    private JButton btnAdminRefresh;
    private JLabel lblAdminFeedback;

    private DefaultTableModel userLoansTableModel;
    private DefaultTableModel adminLoansTableModel;
    private List<LoanTransaction> loadedUserLoans = Collections.emptyList();
    private List<LoanTransaction> loadedAdminLoans = Collections.emptyList();
    private volatile boolean lastOperationExecutedOffEdt;

    private Runnable onBackToOverview;
    private BooleanSupplier beforeInteractiveSubmit;
    private BooleanSupplier pinConfiguredSupplier;
    private Runnable onPinNotSet;
    private BooleanSupplier isLogoutHandledSupplier;
    private SwingUiSupport.DialogMessageCallback dialogMessageCallback = SwingUiSupport.defaultMessageCallback();
    private SwingUiSupport.ConfirmDialogCallback confirmDialogCallback = SwingUiSupport.defaultConfirmCallback();
    private Consumer<String> onStatusMessage;
    private Consumer<Boolean> offEdtObserver;
    private Supplier<CompletableFuture<Void>> onRefreshRequested;
    private Consumer<String> onOperationError;

    public LoanPanel() {
        this(null, null, null);
    }

    public LoanPanel(WalletOperations walletService, LoanService loanService, AuthService authService) {
        super(new BorderLayout());
        this.walletService = walletService;
        this.loanService = loanService;
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized();
        add(SwingUiSupport.createResponsiveScrollPane(pnlRoot), BorderLayout.CENTER);
        applyRoleAccessControl();
        initInteractions();
    }

    /**
     * Configures models, labels, WrapLayout bars, and UiTheme styling outside {@code $$$setupUI$$$()}
     * so IntelliJ GUI Designer bytecode instrumentation never drops runtime state.
     */
    private void ensureComponentsInitialized() {
        OtogConfig config = OtogConfig.getInstance();
        String currency = config.getDefaultCurrency();

        if (pnlProductSummary != null) {
            pnlProductSummary.setBorder(UiTheme.titledBorder("Configured Simulated Loan Product"));
        }
        if (pnlApplicationForm != null) {
            pnlApplicationForm.setBorder(UiTheme.titledBorder("Simulated Loan Application & Repayment Preview"));
        }
        if (pnlUserLoans != null) {
            pnlUserLoans.setBorder(UiTheme.titledBorder("My Loan Applications & Full Repayment"));
        }
        if (pnlAdminDecision != null) {
            pnlAdminDecision.setBorder(UiTheme.titledBorder("Admin Decision"));
        }
        if (tabsLoan != null) {
            tabsLoan.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
            tabsLoan.setMinimumSize(new Dimension(200, 200));
        }

        if (userLoansTableModel == null) {
            userLoansTableModel = new DefaultTableModel(USER_LOAN_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblUserLoans != null) {
            if (tblUserLoans.getModel() != userLoansTableModel) {
                tblUserLoans.setModel(userLoansTableModel);
            }
            UiTheme.styleTable(tblUserLoans, 110, 125, 85, 80, 75, 90, 90, 115, 90, 140);
            UiTheme.alignTableColumnsRight(tblUserLoans, 2, 3, 4, 5, 6);
        }

        if (adminLoansTableModel == null) {
            adminLoansTableModel = new DefaultTableModel(ADMIN_LOAN_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }
        if (tblAdminLoans != null) {
            if (tblAdminLoans.getModel() != adminLoansTableModel) {
                tblAdminLoans.setModel(adminLoansTableModel);
            }
            UiTheme.styleTable(tblAdminLoans, 110, 125, 115, 105, 85, 90, 90, 115, 90, 145);
            UiTheme.alignTableColumnsRight(tblAdminLoans, 4, 5, 6);
        }

        if (lblPrincipalCaption != null) {
            lblPrincipalCaption.setText("Principal Amount (" + currency + "):");
        }
        if (lblProductName != null) {
            lblProductName.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
            lblProductName.setText(config.getLoanProductName() + " (" + config.getLoanProductCode() + ") [SIMULATED]");
        }
        if (lblProductTerms != null) {
            lblProductTerms.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
            lblProductTerms.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
            BigDecimal ratePct = config.getLoanMonthlyInterestRate().multiply(new BigDecimal("100"))
                    .setScale(2, MoneyUtil.DEFAULT_ROUNDING);
            BigDecimal feePct = config.getLoanProcessingFeePercent().multiply(new BigDecimal("100"))
                    .setScale(2, MoneyUtil.DEFAULT_ROUNDING);
            lblProductTerms.setText("Principal " + MoneyUtil.format(config.getLoanMinPrincipal(), currency)
                    + " - " + MoneyUtil.format(config.getLoanMaxPrincipal(), currency)
                    + " | " + ratePct.toPlainString() + "%/mo | " + config.getLoanTermMonths() + " mo"
                    + " | Fee: " + MoneyUtil.format(config.getLoanProcessingFeeFlat(), currency)
                    + " + " + feePct.toPlainString() + "% [SIMULATED]");
        }
        if (lblFormulaSummary != null) {
            lblFormulaSummary.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
            lblFormulaSummary.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
            lblFormulaSummary.setText("Interest = Principal x Rate x Term (HALF_EVEN); Total Repayment = Principal + Interest + Processing Fee");
        }
        if (lblOutstandingDue != null) {
            lblOutstandingDue.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
            lblOutstandingDue.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            lblOutstandingDue.setText(MoneyUtil.format(BigDecimal.ZERO, currency));
        }
        if (lblScopeNotice != null) {
            lblScopeNotice.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
            lblScopeNotice.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
        if (lblPreviewSummary != null) {
            lblPreviewSummary.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
            lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        }
        if (lblLoanFeedback != null) {
            lblLoanFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
        if (lblSelectedLoanDue != null) {
            lblSelectedLoanDue.setFont(UiTheme.font(UiTheme.FontRole.LABEL_BOLD));
        }
        if (lblRepayFeedback != null) {
            lblRepayFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }
        if (lblAdminFeedback != null) {
            lblAdminFeedback.setFont(UiTheme.font(UiTheme.FontRole.LABEL));
        }

        UiTheme.styleInputField(txtPrincipal);
        UiTheme.styleInputField(txtRepayPin);
        if (txtRepayPin != null) {
            txtRepayPin.setColumns(10);
            txtRepayPin.setToolTipText("Enter your Transaction PIN to authorize full loan repayment");
        }
        UiTheme.styleInputField(txtAdminReason);
        if (txtAdminReason != null) {
            txtAdminReason.setToolTipText("Optional note for approval; mandatory reason for rejection");
        }

        UiTheme.styleSecondaryButton(btnPreviewLoan);
        UiTheme.stylePrimaryButton(btnApplyLoan);
        UiTheme.styleSecondaryButton(btnBackToOverview);
        if (btnRepayLoan != null) {
            btnRepayLoan.setEnabled(false);
            UiTheme.stylePrimaryButton(btnRepayLoan);
        }
        if (btnAdminApprove != null) {
            btnAdminApprove.setEnabled(false);
            UiTheme.stylePrimaryButton(btnAdminApprove);
        }
        if (btnAdminReject != null) {
            btnAdminReject.setEnabled(false);
            UiTheme.styleSecondaryButton(btnAdminReject);
        }
        UiTheme.styleSecondaryButton(btnAdminRefresh);

        if (pnlApplyActions != null) {
            SwingUiSupport.applyWrapLayout(pnlApplyActions, FlowLayout.LEFT, 8, 4);
        }
        if (pnlRepayControls != null) {
            SwingUiSupport.applyWrapLayout(pnlRepayControls, FlowLayout.LEFT, 8, 4);
        }
        if (pnlAdminActions != null) {
            SwingUiSupport.applyWrapLayout(pnlAdminActions, FlowLayout.LEFT, 8, 4);
        }

        SwingUiSupport.makeShrinkable(
                lblProductName, lblProductTerms, lblFormulaSummary, lblOutstandingDue, lblScopeNotice,
                txtPrincipal, lblPreviewSummary, lblLoanFeedback, lblSelectedLoanDue,
                txtRepayPin, lblRepayFeedback, txtAdminReason, lblAdminFeedback);
    }

    /**
     * Enforces CAP-09/CAP-12 role boundaries: hides the Admin Loan Approvals sub-tab from non-ADMIN users.
     */
    public void applyRoleAccessControl() {
        if (tabsLoan == null || pnlAdminTab == null) {
            return;
        }
        User currentUser = SessionManager.getInstance().getCurrentUser();
        boolean isAdmin = currentUser == null || currentUser.isAdmin();
        int adminTabIdx = tabsLoan.indexOfComponent(pnlAdminTab);
        if (isAdmin && adminTabIdx < 0) {
            tabsLoan.addTab(ADMIN_TAB_TITLE, pnlAdminTab);
        } else if (!isAdmin && adminTabIdx >= 0) {
            tabsLoan.removeTabAt(adminTabIdx);
        }
    }

    private void initInteractions() {
        btnPreviewLoan.addActionListener(e -> handleInteractivePreview());
        btnApplyLoan.addActionListener(e -> handleInteractiveApply());
        txtPrincipal.addActionListener(e -> handleInteractivePreview());
        btnBackToOverview.addActionListener(e -> {
            if (onBackToOverview != null) {
                onBackToOverview.run();
            }
        });
        tblUserLoans.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedUserLoanControls();
            }
        });
        btnRepayLoan.addActionListener(e -> handleInteractiveRepay());
        txtRepayPin.addActionListener(e -> handleInteractiveRepay());

        tblAdminLoans.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedAdminLoanControls();
            }
        });
        btnAdminApprove.addActionListener(e -> handleInteractiveAdminApprove());
        btnAdminReject.addActionListener(e -> handleInteractiveAdminReject());
        btnAdminRefresh.addActionListener(e -> refreshAdminQueueAsync());
    }

    public void setOnBackToOverview(Runnable onBackToOverview) {
        this.onBackToOverview = onBackToOverview;
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

    public void setIsLogoutHandledSupplier(BooleanSupplier isLogoutHandledSupplier) {
        this.isLogoutHandledSupplier = isLogoutHandledSupplier;
    }

    public void setDialogMessageCallback(SwingUiSupport.DialogMessageCallback dialogMessageCallback) {
        this.dialogMessageCallback = dialogMessageCallback != null
                ? dialogMessageCallback
                : SwingUiSupport.defaultMessageCallback();
    }

    public void setConfirmDialogCallback(SwingUiSupport.ConfirmDialogCallback confirmDialogCallback) {
        this.confirmDialogCallback = confirmDialogCallback != null
                ? confirmDialogCallback
                : SwingUiSupport.defaultConfirmCallback();
    }

    public void setOnStatusMessage(Consumer<String> onStatusMessage) {
        this.onStatusMessage = onStatusMessage;
    }

    public void setOffEdtObserver(Consumer<Boolean> offEdtObserver) {
        this.offEdtObserver = offEdtObserver;
    }

    public void setOnRefreshRequested(Supplier<CompletableFuture<Void>> onRefreshRequested) {
        this.onRefreshRequested = onRefreshRequested;
    }

    public void setOnOperationError(Consumer<String> onOperationError) {
        this.onOperationError = onOperationError;
    }

    public boolean isLastOperationExecutedOffEdt() {
        return lastOperationExecutedOffEdt;
    }

    public void focusPrincipal() {
        if (txtPrincipal != null) {
            txtPrincipal.requestFocusInWindow();
        }
    }

    public void updateProductAndLoans(LoanService.LoanProduct product,
                                      List<LoanTransaction> userLoans,
                                      BigDecimal outstandingDue,
                                      List<LoanTransaction> adminQueue) {
        if (product != null) {
            String curr = product.getCurrency();
            BigDecimal ratePct = product.getMonthlyInterestRate().multiply(new BigDecimal("100"))
                    .setScale(2, MoneyUtil.DEFAULT_ROUNDING);
            BigDecimal feePct = product.getProcessingFeePercent().multiply(new BigDecimal("100"))
                    .setScale(2, MoneyUtil.DEFAULT_ROUNDING);
            lblProductName.setText(product.getProductName() + " (" + product.getProductCode() + ") [SIMULATED]");
            lblProductTerms.setText("Principal " + MoneyUtil.format(product.getMinPrincipal(), curr)
                    + " - " + MoneyUtil.format(product.getMaxPrincipal(), curr)
                    + " | " + ratePct.toPlainString() + "%/mo | " + product.getTermMonths() + " mo"
                    + " | Fee: " + MoneyUtil.format(product.getProcessingFeeFlat(), curr)
                    + " + " + feePct.toPlainString() + "% [SIMULATED]");
            lblFormulaSummary.setText(product.getCalculationFormula());
            lblFormulaSummary.setToolTipText(product.getCalculationFormula());
            lblPrincipalCaption.setText("Principal Amount (" + curr + "):");
        }
        String curr = product != null ? product.getCurrency() : OtogConfig.getInstance().getDefaultCurrency();
        BigDecimal safeDue = outstandingDue != null ? outstandingDue : MoneyUtil.ZERO;
        lblOutstandingDue.setText(MoneyUtil.format(safeDue, curr));
        lblOutstandingDue.setForeground(safeDue.compareTo(BigDecimal.ZERO) > 0
                ? UiTheme.color(UiTheme.ColorRole.WARNING)
                : UiTheme.color(UiTheme.ColorRole.SUCCESS));

        this.loadedUserLoans = userLoans != null ? Collections.unmodifiableList(new ArrayList<>(userLoans)) : Collections.emptyList();
        userLoansTableModel.setRowCount(0);
        for (LoanTransaction loan : this.loadedUserLoans) {
            userLoansTableModel.addRow(new Object[]{
                    loan.getLoanId(),
                    loan.getReferenceNumber(),
                    MoneyUtil.format(loan.getPrincipal(), loan.getCurrency()),
                    MoneyUtil.format(loan.getInterestAmount(), loan.getCurrency()),
                    MoneyUtil.format(loan.getProcessingFee(), loan.getCurrency()),
                    MoneyUtil.format(loan.getTotalRepaymentDue(), loan.getCurrency()),
                    MoneyUtil.format(loan.getAmountDue(), loan.getCurrency()),
                    loan.getLoanStatus() != null ? loan.getLoanStatus().name() : "-",
                    loan.getDueDate() != null ? loan.getDueDate().toString() : "-",
                    loan.getDecisionReason() != null ? loan.getDecisionReason() : ""
            });
        }
        updateSelectedUserLoanControls();

        if (adminQueue != null) {
            populateAdminLoansTable(adminQueue);
        }
        applyRoleAccessControl();
    }

    private void populateAdminLoansTable(List<LoanTransaction> adminQueue) {
        this.loadedAdminLoans = Collections.unmodifiableList(new ArrayList<>(adminQueue));
        adminLoansTableModel.setRowCount(0);
        for (LoanTransaction loan : this.loadedAdminLoans) {
            String borrower = (loan.getBorrowerName() != null && !loan.getBorrowerName().isBlank())
                    ? loan.getBorrowerName() + " (" + loan.getBorrowerUserId() + ")"
                    : (loan.getBorrowerUserId() != null ? loan.getBorrowerUserId() : "-");
            adminLoansTableModel.addRow(new Object[]{
                    loan.getLoanId(),
                    loan.getReferenceNumber(),
                    borrower,
                    loan.getBorrowerWalletId(),
                    MoneyUtil.format(loan.getPrincipal(), loan.getCurrency()),
                    MoneyUtil.format(loan.getTotalRepaymentDue(), loan.getCurrency()),
                    MoneyUtil.format(loan.getAmountDue(), loan.getCurrency()),
                    loan.getLoanStatus() != null ? loan.getLoanStatus().name() : "-",
                    loan.getDueDate() != null ? loan.getDueDate().toString() : "-",
                    loan.getDecisionReason() != null ? loan.getDecisionReason() : ""
            });
        }
        updateSelectedAdminLoanControls();
    }

    private LoanTransaction getSelectedUserLoan() {
        if (tblUserLoans == null) {
            return null;
        }
        int viewRow = tblUserLoans.getSelectedRow();
        int modelRow = viewRow >= 0 ? tblUserLoans.convertRowIndexToModel(viewRow) : -1;
        if (modelRow < 0 || modelRow >= loadedUserLoans.size()) {
            return null;
        }
        return loadedUserLoans.get(modelRow);
    }

    private LoanTransaction getSelectedAdminLoan() {
        if (tblAdminLoans == null) {
            return null;
        }
        int viewRow = tblAdminLoans.getSelectedRow();
        int modelRow = viewRow >= 0 ? tblAdminLoans.convertRowIndexToModel(viewRow) : -1;
        if (modelRow < 0 || modelRow >= loadedAdminLoans.size()) {
            return null;
        }
        return loadedAdminLoans.get(modelRow);
    }

    private void updateSelectedUserLoanControls() {
        LoanTransaction selected = getSelectedUserLoan();
        boolean canRepay = selected != null && selected.getLoanStatus() == LoanStatus.DISBURSED && !operationInFlight.get();
        if (btnRepayLoan != null) {
            btnRepayLoan.setEnabled(canRepay);
        }
        if (lblSelectedLoanDue != null) {
            if (selected == null) {
                lblSelectedLoanDue.setText("Selected Due: --");
            } else {
                lblSelectedLoanDue.setText("Selected Due (" + selected.getLoanId() + "): "
                        + MoneyUtil.format(selected.getAmountDue(), selected.getCurrency())
                        + " [" + selected.getLoanStatus() + "]");
            }
        }
    }

    private void updateSelectedAdminLoanControls() {
        LoanTransaction selected = getSelectedAdminLoan();
        boolean isPending = selected != null && selected.getLoanStatus() == LoanStatus.PENDING_APPROVAL && !operationInFlight.get();
        if (btnAdminApprove != null) {
            btnAdminApprove.setEnabled(isPending);
        }
        if (btnAdminReject != null) {
            btnAdminReject.setEnabled(isPending);
        }
    }

    public CompletableFuture<LoanService.LoanPreview> previewLoanAsync(String principalText) {
        CompletableFuture<LoanService.LoanPreview> future = new CompletableFuture<>();
        if (loanService == null) {
            future.completeExceptionally(new IllegalStateException("LoanService is not enabled."));
            return future;
        }
        SwingUiSupport.runOnEdt(() -> {
            if (btnPreviewLoan != null) {
                btnPreviewLoan.setEnabled(false);
            }
            if (lblPreviewSummary != null) {
                lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblPreviewSummary.setText("Calculating repayment preview...");
            }
        });

        new SwingWorker<LoanService.LoanPreview, Void>() {
            @Override
            protected LoanService.LoanPreview doInBackground() {
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                Wallet wallet = resolveActiveWallet(session);
                BigDecimal principal = parseRequiredDecimal(principalText);
                return loanService.previewLoanApplication(wallet.getWalletId(), principal);
            }

            @Override
            protected void done() {
                if (btnPreviewLoan != null && !operationInFlight.get()) {
                    btnPreviewLoan.setEnabled(true);
                }
                try {
                    LoanService.LoanPreview preview = get();
                    String curr = preview.getCurrency();
                    if (lblPreviewSummary != null) {
                        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.PRIMARY));
                        lblPreviewSummary.setText("Principal " + MoneyUtil.format(preview.getPrincipal(), curr)
                                + " + Interest " + MoneyUtil.format(preview.getInterestAmount(), curr)
                                + " + Fee " + MoneyUtil.format(preview.getProcessingFee(), curr)
                                + " = Total Repayment " + MoneyUtil.format(preview.getTotalRepaymentDue(), curr)
                                + " (Due " + preview.getEstimatedDueDate() + ") [SIMULATED]");
                    }
                    if (lblLoanFeedback != null) {
                        lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                        lblLoanFeedback.setText("Preview ready — review total repayment, then click Submit Application.");
                    }
                    future.complete(preview);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    if (lblPreviewSummary != null) {
                        lblPreviewSummary.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblPreviewSummary.setText("Preview failed: " + msg);
                    }
                    if (lblLoanFeedback != null) {
                        lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblLoanFeedback.setText("Preview failed: " + msg);
                    }
                    if (onOperationError != null) {
                        onOperationError.accept(msg);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<LoanTransaction> submitLoanApplicationAsync(String principalText) {
        return executeLoanApplicationAsync(null, principalText, false);
    }

    private CompletableFuture<LoanTransaction> executeLoanApplicationAsync(
            String explicitReference, String principalText, boolean releaseInFlightOnDone) {
        CompletableFuture<LoanTransaction> future = new CompletableFuture<>();
        if (loanService == null) {
            if (releaseInFlightOnDone) {
                operationInFlight.set(false);
            }
            future.completeExceptionally(new IllegalStateException("LoanService is not enabled."));
            return future;
        }
        SwingUiSupport.runOnEdt(() -> {
            setApplyControlsEnabled(false);
            if (lblLoanFeedback != null) {
                lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblLoanFeedback.setText("Submitting loan application...");
            }
            notifyStatus("Submitting simulated loan application...");
        });

        new SwingWorker<LoanTransaction, Void>() {
            @Override
            protected LoanTransaction doInBackground() {
                recordOffEdt();
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                Wallet wallet = resolveActiveWallet(session);
                BigDecimal principal = parseRequiredDecimal(principalText);
                String ref = (explicitReference != null && !explicitReference.trim().isEmpty())
                        ? explicitReference.trim()
                        : IdGenerator.nextReferenceNumber();
                return loanService.applyForLoan(ref, wallet.getWalletId(), principal);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    operationInFlight.set(false);
                }
                setApplyControlsEnabled(true);
                try {
                    LoanTransaction loan = get();
                    if (txtPrincipal != null) {
                        txtPrincipal.setText("");
                    }
                    if (lblLoanFeedback != null) {
                        lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblLoanFeedback.setText("Application submitted: " + loan.getLoanId()
                                + " (Pending admin approval; Total Due on approval: "
                                + MoneyUtil.format(loan.getTotalRepaymentDue(), loan.getCurrency()) + ")");
                    }
                    notifyStatus("Loan application submitted (" + loan.getLoanId() + ") — pending admin approval");
                    triggerRefreshThenComplete(loan, future);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    if (lblLoanFeedback != null) {
                        lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblLoanFeedback.setText("Application failed: " + msg);
                    }
                    notifyStatus("Loan application failed: " + msg);
                    if (onOperationError != null) {
                        onOperationError.accept(msg);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<LoanTransaction> submitRepayLoanAsync(String loanIdOrTxnId, String rawPin) {
        return executeRepayLoanAsync(loanIdOrTxnId, rawPin, false);
    }

    private CompletableFuture<LoanTransaction> executeRepayLoanAsync(
            String loanIdOrTxnId, String rawPin, boolean releaseInFlightOnDone) {
        CompletableFuture<LoanTransaction> future = new CompletableFuture<>();
        if (loanService == null) {
            if (releaseInFlightOnDone) {
                operationInFlight.set(false);
            }
            future.completeExceptionally(new IllegalStateException("LoanService is not enabled."));
            return future;
        }
        SwingUiSupport.runOnEdt(() -> {
            if (btnRepayLoan != null) {
                btnRepayLoan.setEnabled(false);
            }
            if (lblRepayFeedback != null) {
                lblRepayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblRepayFeedback.setText("Processing full loan repayment...");
            }
            notifyStatus("Processing full loan repayment...");
        });

        new SwingWorker<LoanTransaction, Void>() {
            @Override
            protected LoanTransaction doInBackground() {
                recordOffEdt();
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User user = session.getCurrentUser();
                Wallet wallet = resolveActiveWallet(session);
                String ref = IdGenerator.nextReferenceNumber();
                String cleanPin = rawPin != null ? rawPin.trim() : "";

                if (!cleanPin.isEmpty()) {
                    if (authService != null && user != null) {
                        AuthService.verifyPinOrThrow(authService, user.getUserId(), cleanPin);
                        return loanService.repayLoanFull(ref, wallet.getWalletId(), loanIdOrTxnId);
                    }
                    return loanService.repayLoanFullWithPin(ref, wallet.getWalletId(), loanIdOrTxnId, cleanPin);
                }
                return loanService.repayLoanFull(ref, wallet.getWalletId(), loanIdOrTxnId);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    operationInFlight.set(false);
                }
                updateSelectedUserLoanControls();
                try {
                    LoanTransaction repayTxn = get();
                    if (lblRepayFeedback != null) {
                        lblRepayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblRepayFeedback.setText("Loan " + repayTxn.getLoanId() + " repaid in full: "
                                + MoneyUtil.format(repayTxn.getTotalAmount(), repayTxn.getCurrency())
                                + " (Ref: " + repayTxn.getReferenceNumber() + ")");
                    }
                    notifyStatus("Loan repaid in full (Ref: " + repayTxn.getReferenceNumber() + ")");
                    triggerRefreshThenComplete(repayTxn, future);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    if (lblRepayFeedback != null) {
                        lblRepayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblRepayFeedback.setText("Repayment failed: " + msg);
                    }
                    notifyStatus("Loan repayment failed: " + msg);
                    if (onOperationError != null) {
                        onOperationError.accept(msg);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<LoanTransaction> submitAdminApproveAsync(String loanIdOrTxnId, String reason) {
        return executeAdminDecisionAsync(true, loanIdOrTxnId, reason, false);
    }

    public CompletableFuture<LoanTransaction> submitAdminRejectAsync(String loanIdOrTxnId, String reason) {
        return executeAdminDecisionAsync(false, loanIdOrTxnId, reason, false);
    }

    private CompletableFuture<LoanTransaction> executeAdminDecisionAsync(
            boolean approve, String loanIdOrTxnId, String reason, boolean releaseInFlightOnDone) {
        CompletableFuture<LoanTransaction> future = new CompletableFuture<>();
        if (loanService == null) {
            if (releaseInFlightOnDone) {
                operationInFlight.set(false);
            }
            future.completeExceptionally(new IllegalStateException("LoanService is not enabled."));
            return future;
        }
        String verb = approve ? "Approving & disbursing" : "Rejecting";
        SwingUiSupport.runOnEdt(() -> {
            if (btnAdminApprove != null) {
                btnAdminApprove.setEnabled(false);
            }
            if (btnAdminReject != null) {
                btnAdminReject.setEnabled(false);
            }
            if (lblAdminFeedback != null) {
                lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblAdminFeedback.setText(verb + " loan " + loanIdOrTxnId + "...");
            }
            notifyStatus(verb + " loan " + loanIdOrTxnId + "...");
        });

        new SwingWorker<LoanTransaction, Void>() {
            @Override
            protected LoanTransaction doInBackground() {
                recordOffEdt();
                SessionManager session = SessionManager.getInstance();
                session.requireAuthentication();
                User admin = session.getCurrentUser();
                String adminId = admin != null ? admin.getUserId() : null;
                return approve
                        ? loanService.approveLoan(adminId, loanIdOrTxnId, reason)
                        : loanService.rejectLoan(adminId, loanIdOrTxnId, reason);
            }

            @Override
            protected void done() {
                if (releaseInFlightOnDone) {
                    operationInFlight.set(false);
                }
                updateSelectedAdminLoanControls();
                try {
                    LoanTransaction updated = get();
                    if (txtAdminReason != null) {
                        txtAdminReason.setText("");
                    }
                    if (lblAdminFeedback != null) {
                        lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                        lblAdminFeedback.setText(approve
                                ? "Approved & disbursed " + updated.getLoanId() + " ("
                                + MoneyUtil.format(updated.getPrincipal(), updated.getCurrency())
                                + " credited; Due: " + MoneyUtil.format(updated.getAmountDue(), updated.getCurrency()) + ")"
                                : "Rejected " + updated.getLoanId() + " (Reason: " + updated.getDecisionReason() + ")");
                    }
                    notifyStatus((approve ? "Loan approved & disbursed: " : "Loan rejected: ") + updated.getLoanId());
                    triggerRefreshThenComplete(updated, future);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    if (lblAdminFeedback != null) {
                        lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblAdminFeedback.setText((approve ? "Approval" : "Rejection") + " failed: " + msg);
                    }
                    notifyStatus("Loan decision failed: " + msg);
                    if (onOperationError != null) {
                        onOperationError.accept(msg);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<List<LoanTransaction>> refreshAdminQueueAsync() {
        CompletableFuture<List<LoanTransaction>> future = new CompletableFuture<>();
        if (loanService == null) {
            future.complete(Collections.emptyList());
            return future;
        }
        User current = SessionManager.getInstance().getCurrentUser();
        if (current == null || !current.isAdmin()) {
            if (lblAdminFeedback != null) {
                lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
                lblAdminFeedback.setText("Administrator privileges are required to view the loan approval queue.");
            }
            future.complete(Collections.emptyList());
            return future;
        }
        SwingUiSupport.runOnEdt(() -> {
            if (btnAdminRefresh != null) {
                btnAdminRefresh.setEnabled(false);
            }
            if (lblAdminFeedback != null) {
                lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                lblAdminFeedback.setText("Refreshing loan approval queue...");
            }
        });
        String adminId = current.getUserId();
        new SwingWorker<List<LoanTransaction>, Void>() {
            @Override
            protected List<LoanTransaction> doInBackground() {
                return loanService.getAllLoanApplications(adminId);
            }

            @Override
            protected void done() {
                if (btnAdminRefresh != null) {
                    btnAdminRefresh.setEnabled(true);
                }
                try {
                    List<LoanTransaction> all = get();
                    populateAdminLoansTable(all);
                    if (lblAdminFeedback != null) {
                        lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
                        lblAdminFeedback.setText("Loaded " + all.size() + " loan application(s).");
                    }
                    future.complete(all);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String msg = SwingUiSupport.extractErrorMessage(cause);
                    if (lblAdminFeedback != null) {
                        lblAdminFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                        lblAdminFeedback.setText("Queue refresh failed: " + msg);
                    }
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private void handleInteractivePreview() {
        if (operationInFlight.get()) {
            return;
        }
        if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || loanService == null) {
            return;
        }
        String principalText = txtPrincipal.getText() != null ? txtPrincipal.getText().trim() : "";
        previewLoanAsync(principalText);
    }

    private void handleInteractiveApply() {
        if (!operationInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || loanService == null) {
                return;
            }
            String principalText = txtPrincipal.getText() != null ? txtPrincipal.getText().trim() : "";
            try {
                parseRequiredDecimal(principalText);
            } catch (RuntimeException ex) {
                String msg = SwingUiSupport.extractErrorMessage(ex);
                lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblLoanFeedback.setText("Application failed: " + msg);
                showMessage("Invalid Loan Amount", msg, JOptionPane.ERROR_MESSAGE);
                txtPrincipal.requestFocusInWindow();
                return;
            }

            setApplyControlsEnabled(false);
            asyncStarted = true;
            previewLoanAsync(principalText).whenComplete((preview, previewErr) -> SwingUiSupport.runOnEdt(() -> {
                if (previewErr != null) {
                    operationInFlight.set(false);
                    setApplyControlsEnabled(true);
                    Throwable cause = SwingUiSupport.unwrapCause(previewErr);
                    showMessage("Loan Preview Failed", SwingUiSupport.extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                JTextArea area = new JTextArea(preview.toConfirmationText());
                area.setEditable(false);
                area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                area.setMargin(new Insets(6, 8, 6, 8));
                int choice = confirmDialogCallback.show(
                        this,
                        "Confirm Simulated Loan Application",
                        new JScrollPane(area),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (choice != JOptionPane.OK_OPTION) {
                    operationInFlight.set(false);
                    setApplyControlsEnabled(true);
                    lblLoanFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                    lblLoanFeedback.setText("Loan application cancelled.");
                    return;
                }
                executeLoanApplicationAsync(preview.getReferenceNumber(), preview.getPrincipal().toPlainString(), true)
                        .whenComplete((loan, err) -> SwingUiSupport.runOnEdt(() -> {
                            boolean loggedOut = isLogoutHandledSupplier != null && isLogoutHandledSupplier.getAsBoolean();
                            if (err != null && !loggedOut) {
                                showMessage("Loan Application Failed",
                                        SwingUiSupport.extractErrorMessage(SwingUiSupport.unwrapCause(err)),
                                        JOptionPane.ERROR_MESSAGE);
                            } else if (loan != null) {
                                showMessage("Loan Application Submitted",
                                        "Loan ID: " + loan.getLoanId()
                                                + "\nReference: " + loan.getReferenceNumber()
                                                + "\nPrincipal: " + MoneyUtil.format(loan.getPrincipal(), loan.getCurrency())
                                                + "\nTotal Repayment (on approval): "
                                                + MoneyUtil.format(loan.getTotalRepaymentDue(), loan.getCurrency())
                                                + "\nStatus: PENDING_APPROVAL (awaiting administrator decision)",
                                        JOptionPane.INFORMATION_MESSAGE);
                            }
                        }));
            }));
        } finally {
            if (!asyncStarted) {
                operationInFlight.set(false);
            }
        }
    }

    private void handleInteractiveRepay() {
        if (!operationInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || loanService == null) {
                return;
            }
            LoanTransaction selected = getSelectedUserLoan();
            if (selected == null) {
                showMessage("No Loan Selected", "Please select a disbursed loan row to repay in full.",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selected.getLoanStatus() != LoanStatus.DISBURSED) {
                showMessage("Loan Not Eligible",
                        "Only DISBURSED loans can be repaid. Selected status: " + selected.getLoanStatus(),
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            String pin = new String(txtRepayPin.getPassword()).trim();
            boolean pinSet = pinConfiguredSupplier == null || pinConfiguredSupplier.getAsBoolean();
            if (authService != null && !pinSet) {
                txtRepayPin.setText("");
                if (onPinNotSet != null) {
                    onPinNotSet.run();
                }
                return;
            }
            if (authService != null && pin.isEmpty()) {
                OtogConfig config = OtogConfig.getInstance();
                String msg = "Please enter your " + config.getPinMinLength() + "-" + config.getPinMaxLength()
                        + " digit Transaction PIN to authorize full loan repayment.";
                lblRepayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                lblRepayFeedback.setText("Repayment failed: " + msg);
                showMessage("PIN Required", msg, JOptionPane.WARNING_MESSAGE);
                txtRepayPin.requestFocusInWindow();
                return;
            }
            txtRepayPin.setText("");

            int choice = confirmDialogCallback.show(
                    this,
                    "Confirm Full Loan Repayment",
                    "Repay loan " + selected.getLoanId() + " in full?\n\n"
                            + "Principal: " + MoneyUtil.format(selected.getPrincipal(), selected.getCurrency()) + "\n"
                            + "Interest: " + MoneyUtil.format(selected.getInterestAmount(), selected.getCurrency()) + "\n"
                            + "Processing Fee: " + MoneyUtil.format(selected.getProcessingFee(), selected.getCurrency()) + "\n"
                            + "Total Full Repayment Debit: " + MoneyUtil.format(selected.getAmountDue(), selected.getCurrency()),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                lblRepayFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                lblRepayFeedback.setText("Loan repayment cancelled.");
                return;
            }

            asyncStarted = true;
            executeRepayLoanAsync(selected.getLoanId(), pin, true).whenComplete((txn, err) -> SwingUiSupport.runOnEdt(() -> {
                boolean loggedOut = isLogoutHandledSupplier != null && isLogoutHandledSupplier.getAsBoolean();
                if (err != null && !loggedOut) {
                    showMessage("Loan Repayment Failed",
                            SwingUiSupport.extractErrorMessage(SwingUiSupport.unwrapCause(err)),
                            JOptionPane.ERROR_MESSAGE);
                } else if (txn != null) {
                    showMessage("Loan Repaid in Full",
                            "Loan " + txn.getLoanId() + " has been repaid in full!\n"
                                    + "Amount Debited: " + MoneyUtil.format(txn.getTotalAmount(), txn.getCurrency()) + "\n"
                                    + "Reference: " + txn.getReferenceNumber(),
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }));
        } finally {
            if (!asyncStarted) {
                operationInFlight.set(false);
            }
        }
    }

    private void handleInteractiveAdminApprove() {
        if (!operationInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || loanService == null) {
                return;
            }
            LoanTransaction selected = getSelectedAdminLoan();
            if (selected == null || selected.getLoanStatus() != LoanStatus.PENDING_APPROVAL) {
                showMessage("Select Pending Loan", "Please select a PENDING_APPROVAL loan application to approve.",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String reason = txtAdminReason != null && txtAdminReason.getText() != null
                    && !txtAdminReason.getText().trim().isEmpty()
                    ? txtAdminReason.getText().trim()
                    : "Approved by administrator";
            int choice = confirmDialogCallback.show(
                    this,
                    "Confirm Loan Approval & Single Disbursement",
                    "Approve loan " + selected.getLoanId() + " and credit "
                            + MoneyUtil.format(selected.getPrincipal(), selected.getCurrency())
                            + " to wallet " + selected.getBorrowerWalletId() + "?\n"
                            + "Total Repayment Due: " + MoneyUtil.format(selected.getTotalRepaymentDue(), selected.getCurrency()),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            asyncStarted = true;
            executeAdminDecisionAsync(true, selected.getLoanId(), reason, true)
                    .whenComplete((loan, err) -> SwingUiSupport.runOnEdt(() -> {
                        if (err != null) {
                            showMessage("Approval Failed",
                                    SwingUiSupport.extractErrorMessage(SwingUiSupport.unwrapCause(err)),
                                    JOptionPane.ERROR_MESSAGE);
                        } else if (loan != null) {
                            showMessage("Loan Approved & Disbursed",
                                    "Loan " + loan.getLoanId() + " approved.\n"
                                            + "Disbursed once: " + MoneyUtil.format(loan.getPrincipal(), loan.getCurrency())
                                            + "\nAmount Due: " + MoneyUtil.format(loan.getAmountDue(), loan.getCurrency()),
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    }));
        } finally {
            if (!asyncStarted) {
                operationInFlight.set(false);
            }
        }
    }

    private void handleInteractiveAdminReject() {
        if (!operationInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean asyncStarted = false;
        try {
            if ((beforeInteractiveSubmit != null && !beforeInteractiveSubmit.getAsBoolean()) || loanService == null) {
                return;
            }
            LoanTransaction selected = getSelectedAdminLoan();
            if (selected == null || selected.getLoanStatus() != LoanStatus.PENDING_APPROVAL) {
                showMessage("Select Pending Loan", "Please select a PENDING_APPROVAL loan application to reject.",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String reason = txtAdminReason != null && txtAdminReason.getText() != null
                    ? txtAdminReason.getText().trim() : "";
            if (reason.isEmpty()) {
                showMessage("Reason Required", "Please enter a decision reason before rejecting a loan application.",
                        JOptionPane.WARNING_MESSAGE);
                if (txtAdminReason != null) {
                    txtAdminReason.requestFocusInWindow();
                }
                return;
            }
            asyncStarted = true;
            executeAdminDecisionAsync(false, selected.getLoanId(), reason, true)
                    .whenComplete((loan, err) -> SwingUiSupport.runOnEdt(() -> {
                        if (err != null) {
                            showMessage("Rejection Failed",
                                    SwingUiSupport.extractErrorMessage(SwingUiSupport.unwrapCause(err)),
                                    JOptionPane.ERROR_MESSAGE);
                        } else if (loan != null) {
                            showMessage("Loan Application Rejected",
                                    "Loan " + loan.getLoanId() + " rejected.\nNo wallet funds were disbursed.",
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    }));
        } finally {
            if (!asyncStarted) {
                operationInFlight.set(false);
            }
        }
    }

    private void setApplyControlsEnabled(boolean enabled) {
        if (btnApplyLoan != null) {
            btnApplyLoan.setEnabled(enabled);
        }
        if (btnPreviewLoan != null) {
            btnPreviewLoan.setEnabled(enabled);
        }
    }

    private void recordOffEdt() {
        boolean offEdt = !SwingUtilities.isEventDispatchThread();
        lastOperationExecutedOffEdt = offEdt;
        if (offEdtObserver != null) {
            offEdtObserver.accept(offEdt);
        }
    }

    private Wallet resolveActiveWallet(SessionManager session) {
        Wallet wallet = session.getCurrentWallet();
        if (wallet == null && walletService != null) {
            wallet = session.refreshWallet(walletService);
        }
        if (wallet == null) {
            throw new IllegalStateException("Active wallet is required.");
        }
        return wallet;
    }

    private void triggerRefreshThenComplete(LoanTransaction result, CompletableFuture<LoanTransaction> future) {
        CompletableFuture<Void> refresh = onRefreshRequested != null ? onRefreshRequested.get() : null;
        if (refresh != null) {
            refresh.whenComplete((v, err) -> future.complete(result));
        } else {
            future.complete(result);
        }
    }

    private static BigDecimal parseRequiredDecimal(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter a positive principal amount.");
        }
        try {
            return new BigDecimal(text.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric amount: " + text.trim());
        }
    }

    private void showMessage(String title, String message, int messageType) {
        dialogMessageCallback.show(this, title, message, messageType);
    }

    private void notifyStatus(String message) {
        if (onStatusMessage != null) {
            onStatusMessage.accept(message);
        }
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTabbedPane getTabs() {
        return tabsLoan;
    }

    public JLabel getProductNameLabel() {
        return lblProductName;
    }

    public JLabel getProductTermsLabel() {
        return lblProductTerms;
    }

    public JLabel getFormulaSummaryLabel() {
        return lblFormulaSummary;
    }

    public JLabel getOutstandingDueLabel() {
        return lblOutstandingDue;
    }

    public JTextField getPrincipalField() {
        return txtPrincipal;
    }

    public JLabel getPreviewSummaryLabel() {
        return lblPreviewSummary;
    }

    public JButton getPreviewLoanButton() {
        return btnPreviewLoan;
    }

    public JButton getApplyLoanButton() {
        return btnApplyLoan;
    }

    public JButton getBackToOverviewButton() {
        return btnBackToOverview;
    }

    public JLabel getLoanFeedbackLabel() {
        return lblLoanFeedback;
    }

    public JTable getUserLoansTable() {
        return tblUserLoans;
    }

    public DefaultTableModel getUserLoansTableModel() {
        return userLoansTableModel;
    }

    public JPasswordField getRepayPinField() {
        return txtRepayPin;
    }

    public JButton getRepayLoanButton() {
        return btnRepayLoan;
    }

    public JLabel getRepayFeedbackLabel() {
        return lblRepayFeedback;
    }

    public JTable getAdminLoansTable() {
        return tblAdminLoans;
    }

    public DefaultTableModel getAdminLoansTableModel() {
        return adminLoansTableModel;
    }

    public JTextField getAdminReasonField() {
        return txtAdminReason;
    }

    public JButton getAdminApproveButton() {
        return btnAdminApprove;
    }

    public JButton getAdminRejectButton() {
        return btnAdminReject;
    }

    public JButton getAdminRefreshButton() {
        return btnAdminRefresh;
    }

    public JLabel getAdminFeedbackLabel() {
        return lblAdminFeedback;
    }

    private void $$$setupUI$$$() {
        OtogConfig config = OtogConfig.getInstance();
        String currency = config.getDefaultCurrency();

        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(1, 1, new Insets(10, 10, 10, 10), 8, 8));

        tabsLoan = new JTabbedPane();
        pnlRoot.add(tabsLoan, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                new Dimension(200, 200), null, null, 0, false));

        pnlBorrowerTab = new JPanel();
        pnlBorrowerTab.setLayout(new GridLayoutManager(3, 1, new Insets(10, 10, 10, 10), 8, 8));
        tabsLoan.addTab("My Loans & Application", pnlBorrowerTab);

        pnlProductSummary = new JPanel();
        pnlProductSummary.setLayout(new GridLayoutManager(4, 4, new Insets(8, 10, 8, 10), 10, 6));
        pnlProductSummary.setBorder(UiTheme.titledBorder("Configured Simulated Loan Product"));
        pnlBorrowerTab.add(pnlProductSummary, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblProductName = new JLabel(config.getLoanProductName() + " (" + config.getLoanProductCode() + ") [SIMULATED]");
        lblProductTerms = new JLabel("Principal " + MoneyUtil.format(config.getLoanMinPrincipal(), currency)
                + " - " + MoneyUtil.format(config.getLoanMaxPrincipal(), currency));
        lblFormulaSummary = new JLabel("Total Repayment = Principal + (Principal x Rate x Term) + Processing Fee");
        lblOutstandingDue = new JLabel(MoneyUtil.format(BigDecimal.ZERO, currency));
        lblScopeNotice = new JLabel("Full lump-sum only (installments/partial/penalties deferred)");

        addSummarySpanRow(pnlProductSummary, 0, "Product:", lblProductName);
        addSummarySpanRow(pnlProductSummary, 1, "Terms & Rate:", lblProductTerms);
        addSummarySpanRow(pnlProductSummary, 2, "Calculation:", lblFormulaSummary);
        addSummaryCell(pnlProductSummary, 3, 0, "Amount Due:", lblOutstandingDue);
        addSummaryCell(pnlProductSummary, 3, 2, "Repayment:", lblScopeNotice);

        pnlApplicationForm = new JPanel();
        pnlApplicationForm.setLayout(new GridLayoutManager(4, 2, new Insets(8, 10, 8, 10), 10, 6));
        pnlApplicationForm.setBorder(UiTheme.titledBorder("Simulated Loan Application & Repayment Preview"));
        pnlBorrowerTab.add(pnlApplicationForm, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblPrincipalCaption = new JLabel("Principal Amount (" + currency + "):");
        UiTheme.styleFormLabel(lblPrincipalCaption);
        txtPrincipal = new JTextField(16);
        pnlApplicationForm.add(lblPrincipalCaption, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        pnlApplicationForm.add(txtPrincipal, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblPreviewSummary = new JLabel("Enter principal amount and click Preview Repayment.");
        addFormRow(pnlApplicationForm, 1, "Repayment Preview:", lblPreviewSummary);

        pnlApplyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnPreviewLoan = new JButton("Preview Repayment");
        btnApplyLoan = new JButton("Submit Application");
        btnBackToOverview = new JButton("\u2190 Back to Overview");
        pnlApplyActions.add(btnPreviewLoan);
        pnlApplyActions.add(btnApplyLoan);
        pnlApplyActions.add(btnBackToOverview);
        pnlApplicationForm.add(pnlApplyActions, new GridConstraints(2, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblLoanFeedback = new JLabel(" ");
        pnlApplicationForm.add(lblLoanFeedback, new GridConstraints(3, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlUserLoans = new JPanel();
        pnlUserLoans.setLayout(new GridLayoutManager(3, 1, new Insets(6, 8, 6, 8), 6, 6));
        pnlUserLoans.setBorder(UiTheme.titledBorder("My Loan Applications & Full Repayment"));
        pnlBorrowerTab.add(pnlUserLoans, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                new Dimension(200, 160), null, null, 0, false));

        tblUserLoans = new JTable();
        scrollUserLoans = new JScrollPane(tblUserLoans);
        pnlUserLoans.add(scrollUserLoans, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                new Dimension(180, 110), new Dimension(360, 145), null, 0, false));

        pnlRepayControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        lblSelectedLoanDue = new JLabel("Selected Due: --");
        JLabel lblRepayPin = new JLabel("Transaction PIN:");
        UiTheme.styleFormLabel(lblRepayPin);
        txtRepayPin = new JPasswordField(10);
        btnRepayLoan = new JButton("Repay Full Amount");
        btnRepayLoan.setEnabled(false);
        pnlRepayControls.add(lblSelectedLoanDue);
        pnlRepayControls.add(lblRepayPin);
        pnlRepayControls.add(txtRepayPin);
        pnlRepayControls.add(btnRepayLoan);
        pnlUserLoans.add(pnlRepayControls, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblRepayFeedback = new JLabel(" ");
        pnlUserLoans.add(lblRepayFeedback, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlAdminTab = new JPanel();
        pnlAdminTab.setLayout(new GridLayoutManager(3, 1, new Insets(10, 10, 10, 10), 8, 8));
        tabsLoan.addTab(ADMIN_TAB_TITLE, pnlAdminTab);

        tblAdminLoans = new JTable();
        scrollAdminLoans = new JScrollPane(tblAdminLoans);
        scrollAdminLoans.setBorder(UiTheme.titledBorder("All Loan Applications (Admin Review & Single Disbursement)"));
        pnlAdminTab.add(scrollAdminLoans, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                new Dimension(180, 140), new Dimension(360, 220), null, 0, false));

        pnlAdminDecision = new JPanel();
        pnlAdminDecision.setLayout(new GridLayoutManager(2, 2, new Insets(8, 10, 8, 10), 10, 6));
        pnlAdminDecision.setBorder(UiTheme.titledBorder("Admin Decision"));
        pnlAdminTab.add(pnlAdminDecision, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtAdminReason = new JTextField(24);
        addFormRow(pnlAdminDecision, 0, "Decision Reason:", txtAdminReason);

        pnlAdminActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnAdminApprove = new JButton("Approve & Disburse");
        btnAdminApprove.setEnabled(false);
        btnAdminReject = new JButton("Reject Application");
        btnAdminReject.setEnabled(false);
        btnAdminRefresh = new JButton("Refresh Queue");
        pnlAdminActions.add(btnAdminApprove);
        pnlAdminActions.add(btnAdminReject);
        pnlAdminActions.add(btnAdminRefresh);
        pnlAdminDecision.add(pnlAdminActions, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblAdminFeedback = new JLabel(" ");
        pnlAdminTab.add(lblAdminFeedback, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    private static void addSummarySpanRow(JPanel panel, int row, String labelText, JLabel valueLabel) {
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        panel.add(label, new GridConstraints(row, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(valueLabel, new GridConstraints(row, 1, 1, 3,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    private static void addSummaryCell(JPanel panel, int row, int labelCol, String labelText, JLabel valueLabel) {
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        panel.add(label, new GridConstraints(row, labelCol, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(valueLabel, new GridConstraints(row, labelCol + 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    private static void addFormRow(JPanel panel, int row, String labelText, JComponent field) {
        JLabel label = new JLabel(labelText);
        UiTheme.styleFormLabel(label);
        panel.add(label, new GridConstraints(row, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        panel.add(field, new GridConstraints(row, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
