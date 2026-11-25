package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.AdminAction;
import com.otog.model.Transaction;
import com.otog.model.User;
import com.otog.model.UserRole;
import com.otog.model.Wallet;
import com.otog.service.AdminService;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;
import com.otog.util.SwingUiSupport;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bound IntelliJ Swing form panel for the Admin Controls tab (CAP-09).
 * All account management and audit queries run off the EDT via SwingWorker.
 */
public class AdminControlsPanel extends JPanel {

    private static final String[] USER_COLUMNS = {"User ID", "Name", "Mobile", "Role", "Status", "Wallet", "Balance"};
    private static final String[] AUDIT_COLUMNS = {"Action ID", "Type", "Target", "Reason", "Timestamp"};

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());

    private static final class AdminPanelSnapshot {
        final List<User> users;
        final Map<String, Wallet> walletsByUser;
        final List<AdminAction> actions;

        private AdminPanelSnapshot(List<User> users, Map<String, Wallet> walletsByUser, List<AdminAction> actions) {
            this.users = users;
            this.walletsByUser = walletsByUser;
            this.actions = actions;
        }
    }

    private final AdminService adminService;
    private final String adminId;
    private final Consumer<Boolean> offEdtObserver;
    private final SwingUiSupport.DialogMessageCallback messageCallback;

    private JPanel pnlRoot;
    private JLabel lblTitle;
    private JSplitPane splitPane;
    private JPanel pnlUsers;
    private JPanel pnlButtons;
    private DefaultTableModel userModel;
    private JTable userTable;
    private DefaultTableModel auditModel;
    private JTable auditTable;
    private JButton btnSuspend;
    private JButton btnReactivate;
    private JButton btnChangeRole;
    private JButton btnViewUserTxns;
    private JButton btnViewAllTxns;
    private JButton btnRefreshAll;
    private JLabel statusLbl;

    public AdminControlsPanel(AdminService adminService,
                              String adminId,
                              Consumer<Boolean> offEdtObserver,
                              SwingUiSupport.DialogMessageCallback messageCallback) {
        super(new BorderLayout());
        this.adminService = adminService;
        this.adminId = adminId;
        this.offEdtObserver = offEdtObserver;
        this.messageCallback = messageCallback != null
                ? messageCallback
                : SwingUiSupport.defaultMessageCallback();

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        ensureComponentsInitialized();
        add(pnlRoot, BorderLayout.CENTER);
        if (adminService != null && adminId != null) {
            wireActionsAndLoad();
        }
    }

    /**
     * Applies table models and runtime UiTheme styling outside {@code $$$setupUI$$$()} so
     * IntelliJ GUI Designer bytecode instrumentation never strips table/button configuration.
     */
    private void ensureComponentsInitialized() {
        if (lblTitle != null) {
            lblTitle.setFont(UiTheme.font(UiTheme.FontRole.TITLE));
        }
        if (userModel == null) {
            userModel = new DefaultTableModel(USER_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
        }
        if (userTable != null) {
            if (userTable.getModel() != userModel) {
                userTable.setModel(userModel);
            }
            userTable.setAutoCreateRowSorter(true);
            UiTheme.styleTable(userTable, 115, 135, 105, 80, 85, 155, 105);
            UiTheme.alignTableColumnsRight(userTable, 6);
        }
        if (auditModel == null) {
            auditModel = new DefaultTableModel(AUDIT_COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int r, int c) {
                    return false;
                }
            };
        }
        if (auditTable != null) {
            if (auditTable.getModel() != auditModel) {
                auditTable.setModel(auditModel);
            }
            auditTable.setAutoCreateRowSorter(true);
            UiTheme.styleTable(auditTable, 115, 130, 115, 290, 145);
        }
        if (btnSuspend != null) {
            btnSuspend.setEnabled(false);
            UiTheme.styleSecondaryButton(btnSuspend);
        }
        if (btnReactivate != null) {
            btnReactivate.setEnabled(false);
            UiTheme.styleSecondaryButton(btnReactivate);
        }
        if (btnChangeRole != null) {
            btnChangeRole.setEnabled(false);
            UiTheme.styleSecondaryButton(btnChangeRole);
        }
        if (btnViewUserTxns != null) {
            btnViewUserTxns.setEnabled(false);
            UiTheme.styleSecondaryButton(btnViewUserTxns);
        }
        UiTheme.styleSecondaryButton(btnViewAllTxns);
        UiTheme.styleSecondaryButton(btnRefreshAll);
        if (statusLbl != null) {
            statusLbl.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
            statusLbl.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        }
    }

    private void wireActionsAndLoad() {
        userTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                boolean hasSelection = userTable.getSelectedRow() >= 0;
                btnSuspend.setEnabled(hasSelection);
                btnReactivate.setEnabled(hasSelection);
                btnChangeRole.setEnabled(hasSelection);
                btnViewUserTxns.setEnabled(hasSelection);
            }
        });

        Consumer<Boolean> reloadAll = showErrorPopup -> {
            btnRefreshAll.setEnabled(false);
            statusLbl.setText("Loading admin records...");
            new SwingWorker<AdminPanelSnapshot, Void>() {
                @Override
                protected AdminPanelSnapshot doInBackground() {
                    if (offEdtObserver != null) {
                        offEdtObserver.accept(!SwingUtilities.isEventDispatchThread());
                    }
                    List<User> users = adminService.getAllUsers(adminId);
                    List<Wallet> wallets = adminService.getAllWallets(adminId);
                    List<AdminAction> actions = adminService.getAdminActions(adminId);
                    Map<String, Wallet> walletsByUser = new HashMap<>();
                    for (Wallet w : wallets) {
                        walletsByUser.put(w.getUserId(), w);
                    }
                    return new AdminPanelSnapshot(users, walletsByUser, actions);
                }

                @Override
                protected void done() {
                    btnRefreshAll.setEnabled(true);
                    try {
                        AdminPanelSnapshot snap = get();
                        userModel.setRowCount(0);
                        for (User u : snap.users) {
                            Wallet w = snap.walletsByUser.get(u.getUserId());
                            userModel.addRow(new Object[]{
                                    u.getUserId(),
                                    u.getName(),
                                    u.getMobileNumber(),
                                    u.getRole(),
                                    u.getStatus(),
                                    w != null ? w.getWalletId() + " (" + w.getStatus() + ")" : "--",
                                    w != null ? MoneyUtil.format(w.getBalance(), w.getCurrency()) : "--"
                            });
                        }
                        auditModel.setRowCount(0);
                        for (AdminAction a : snap.actions) {
                            auditModel.addRow(new Object[]{
                                    a.getActionId(),
                                    a.getActionType(),
                                    a.getTargetUserId(),
                                    a.getReason(),
                                    a.getCreatedAt()
                            });
                        }
                        statusLbl.setText("Last refreshed: " + LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern(OtogConfig.getInstance().getTimestampPattern())));
                    } catch (Exception ex) {
                        Throwable cause = SwingUiSupport.unwrapCause(ex);
                        String message = SwingUiSupport.extractErrorMessage(cause);
                        statusLbl.setText("Error loading admin data: " + message);
                        if (showErrorPopup) {
                            messageCallback.show(pnlRoot, "Admin Refresh Failed", message, JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        };

        reloadAll.accept(false);

        btnSuspend.addActionListener(e -> executeAdminStatusAction(() -> reloadAll.accept(false), true));
        btnReactivate.addActionListener(e -> executeAdminStatusAction(() -> reloadAll.accept(false), false));

        btnChangeRole.addActionListener(e -> {
            int row = userTable.getSelectedRow();
            if (row < 0) {
                return;
            }
            int modelRow = userTable.convertRowIndexToModel(row);
            String targetId = (String) userModel.getValueAt(modelRow, 0);
            String targetName = (String) userModel.getValueAt(modelRow, 1);
            UserRole currentRole = (UserRole) userModel.getValueAt(modelRow, 3);

            UserRole selectedRole = (UserRole) JOptionPane.showInputDialog(
                    pnlRoot,
                    "Select new role for " + targetName + " (" + targetId + "):",
                    "Change User Role",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    UserRole.values(),
                    currentRole
            );
            if (selectedRole == null) {
                return;
            }
            String reason = promptMandatoryAdminReason(pnlRoot,
                    "Enter reason for changing role of " + targetName + " to " + selectedRole + ":",
                    "Change User Role");
            if (reason == null) {
                return;
            }

            statusLbl.setText("Updating role for " + targetId + "...");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    adminService.changeUserRole(adminId, targetId, selectedRole, reason);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        messageCallback.show(pnlRoot, "Done", "Role updated to " + selectedRole + ".",
                                JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        Throwable cause = SwingUiSupport.unwrapCause(ex);
                        messageCallback.show(pnlRoot, "Role Update Failed",
                                "Error: " + SwingUiSupport.extractErrorMessage(cause),
                                JOptionPane.ERROR_MESSAGE);
                    } finally {
                        reloadAll.accept(false);
                    }
                }
            }.execute();
        });

        btnViewUserTxns.addActionListener(e -> {
            int row = userTable.getSelectedRow();
            if (row < 0) {
                return;
            }
            int modelRow = userTable.convertRowIndexToModel(row);
            String targetId = (String) userModel.getValueAt(modelRow, 0);
            String targetName = (String) userModel.getValueAt(modelRow, 1);
            loadAndShowAdminTransactions(targetId,
                    "Transactions — " + targetName + " (" + targetId + ")",
                    () -> reloadAll.accept(false));
        });

        btnViewAllTxns.addActionListener(e ->
                loadAndShowAdminTransactions(null,
                        "All System Transactions",
                        () -> reloadAll.accept(false)));

        btnRefreshAll.addActionListener(e -> reloadAll.accept(true));
    }

    private void executeAdminStatusAction(Runnable reloadAll, boolean suspend) {
        int row = userTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = userTable.convertRowIndexToModel(row);
        String targetId = (String) userModel.getValueAt(modelRow, 0);
        String targetName = (String) userModel.getValueAt(modelRow, 1);

        String verb = suspend ? "suspending" : "reactivating";
        String dialogTitle = suspend ? "Suspend Account" : "Reactivate Account";
        String reason = promptMandatoryAdminReason(userTable,
                "Enter reason for " + verb + " " + targetName + " (" + targetId + "):",
                dialogTitle);
        if (reason == null) {
            return;
        }

        statusLbl.setText((suspend ? "Suspending " : "Reactivating ") + targetId + "...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (suspend) {
                    adminService.suspendUser(adminId, targetId, reason);
                } else {
                    adminService.reactivateUser(adminId, targetId, reason);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    messageCallback.show(userTable, "Done",
                            suspend ? "Account suspended." : "Account reactivated.",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    messageCallback.show(userTable,
                            suspend ? "Suspend Failed" : "Reactivate Failed",
                            "Error: " + SwingUiSupport.extractErrorMessage(cause),
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    reloadAll.run();
                }
            }
        }.execute();
    }

    private String promptMandatoryAdminReason(Component parent, String message, String title) {
        String reason = JOptionPane.showInputDialog(parent, message, title, JOptionPane.QUESTION_MESSAGE);
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            messageCallback.show(parent, "Validation Error", "A reason is required.", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return trimmed;
    }

    private void loadAndShowAdminTransactions(String targetUserId, String dialogTitle, Runnable reloadAll) {
        statusLbl.setText("Loading transactions...");
        new SwingWorker<List<Transaction>, Void>() {
            @Override
            protected List<Transaction> doInBackground() {
                return targetUserId != null
                        ? adminService.getUserTransactions(adminId, targetUserId)
                        : adminService.getAllTransactions(adminId);
            }

            @Override
            protected void done() {
                try {
                    List<Transaction> txns = get();
                    String[] cols = {"Date", "Ref #", "Type", "Source", "Target", "Amount", "Fee", "Status", "Description"};
                    DefaultTableModel model = new DefaultTableModel(cols, 0) {
                        @Override
                        public boolean isCellEditable(int r, int c) {
                            return false;
                        }
                    };
                    for (Transaction t : txns) {
                        model.addRow(new Object[]{
                                t.getCreatedAt() != null ? t.getCreatedAt().format(dateFormatter) : "-",
                                t.getReferenceNumber(),
                                t.getType(),
                                t.getSourceWalletId() != null ? t.getSourceWalletId() : "-",
                                t.getTargetWalletId() != null ? t.getTargetWalletId() : "-",
                                MoneyUtil.format(t.getAmount(), t.getCurrency()),
                                MoneyUtil.format(t.getFee(), t.getCurrency()),
                                t.getStatus(),
                                t.getDescription() != null ? t.getDescription() : ""
                        });
                    }
                    JTable table = new JTable(model);
                    table.setAutoCreateRowSorter(true);
                    UiTheme.styleTable(table, 110, 120, 90, 105, 105, 90, 70, 90, 150);
                    UiTheme.alignTableColumnsRight(table, 5, 6);
                    JScrollPane scroll = new JScrollPane(table);
                    scroll.setPreferredSize(new Dimension(760, 320));
                    JOptionPane.showMessageDialog(pnlRoot, scroll, dialogTitle, JOptionPane.INFORMATION_MESSAGE);
                    reloadAll.run();
                } catch (Exception ex) {
                    Throwable cause = SwingUiSupport.unwrapCause(ex);
                    String message = SwingUiSupport.extractErrorMessage(cause);
                    statusLbl.setText("Error loading transactions: " + message);
                    messageCallback.show(pnlRoot, "Transaction Inspection Failed", "Error: " + message,
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTable getUserTable() {
        return userTable;
    }

    public JTable getAuditTable() {
        return auditTable;
    }

    public JButton getSuspendButton() {
        return btnSuspend;
    }

    public JButton getReactivateButton() {
        return btnReactivate;
    }

    public JButton getChangeRoleButton() {
        return btnChangeRole;
    }

    public JButton getRefreshAllButton() {
        return btnRefreshAll;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(3, 1, new Insets(10, 12, 10, 12), 8, 8));

        lblTitle = new JLabel("Admin Controls — Account Management & Audit Log");
        pnlRoot.add(lblTitle, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.55);
        pnlRoot.add(splitPane, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));

        pnlUsers = new JPanel();
        pnlUsers.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), 4, 4));
        splitPane.setTopComponent(pnlUsers);

        userTable = new JTable();
        JScrollPane userScroll = new JScrollPane(userTable);
        userScroll.setBorder(UiTheme.titledBorder("Registered Accounts & Wallets"));
        pnlUsers.add(userScroll, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, null, null, 0, false));

        pnlButtons = new JPanel();
        pnlButtons.setLayout(new GridLayoutManager(1, 7, new Insets(2, 0, 2, 0), 6, 4));
        pnlUsers.add(pnlButtons, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSuspend = new JButton("Suspend User");
        btnReactivate = new JButton("Reactivate User");
        btnChangeRole = new JButton("Change Role");
        btnViewUserTxns = new JButton("View User Txns");
        btnViewAllTxns = new JButton("All System Txns");
        btnRefreshAll = new JButton("Refresh");

        pnlButtons.add(btnSuspend, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(btnReactivate, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(btnChangeRole, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(btnViewUserTxns, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(btnViewAllTxns, new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(btnRefreshAll, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        pnlButtons.add(new Spacer(), new GridConstraints(0, 6, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));

        auditTable = new JTable();
        JScrollPane auditScroll = new JScrollPane(auditTable);
        auditScroll.setBorder(UiTheme.titledBorder("Admin Action Audit Log"));
        splitPane.setBottomComponent(auditScroll);

        statusLbl = new JLabel("Loading admin records...");
        pnlRoot.add(statusLbl, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
