package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.Notification;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.util.OtogConfig;
import com.otog.util.UiTheme;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;

/**
 * Bound IntelliJ Swing form dialog displaying persistent read/unread notifications
 * for the active authenticated user with off-EDT {@link SwingWorker} operations.
 */
public class NotificationInboxDialog extends JDialog {

    private final WalletOperations walletService;
    private final String userId;
    private final String walletId;
    private final IntConsumer onUnreadCountChanged;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getUiDatePattern());

    private JPanel pnlRoot;
    private JPanel pnlTopBar;
    private JLabel lblUnreadSummary;
    private JCheckBox chkUnreadOnly;
    private JButton btnRefreshInbox;
    private JTable tblNotifications;
    private DefaultTableModel notificationsTableModel;
    private JTextArea txtNotificationDetail;
    private JPanel pnlActions;
    private JButton btnMarkRead;
    private JButton btnMarkUnread;
    private JButton btnMarkAllRead;
    private JButton btnViewLinkedReceipt;
    private JButton btnClose;
    private JLabel lblInboxFeedback;

    private List<Notification> loadedNotifications = Collections.emptyList();
    private volatile boolean lastLoadExecutedOffEdt;

    public NotificationInboxDialog(Frame owner,
                                   WalletOperations walletService,
                                   String userId,
                                   String walletId,
                                   IntConsumer onUnreadCountChanged) {
        super(owner, "Notification Inbox", true);
        this.walletService = walletService;
        this.userId = userId;
        this.walletId = walletId;
        this.onUnreadCountChanged = onUnreadCountChanged;

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        initUI();
    }

    private void initUI() {
        setContentPane(pnlRoot);
        setSize(OtogConfig.getInstance().getUiDimension(
                "ui.notifications.width", "ui.notifications.height", 720, 480));
        setLocationRelativeTo(getParent());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        chkUnreadOnly.addActionListener(e -> loadNotificationsAsync());
        btnRefreshInbox.addActionListener(e -> loadNotificationsAsync());
        btnMarkRead.addActionListener(e -> markSelectedReadAsync(true));
        btnMarkUnread.addActionListener(e -> markSelectedReadAsync(false));
        btnMarkAllRead.addActionListener(e -> markAllReadAsync());
        btnViewLinkedReceipt.addActionListener(e -> openLinkedReceiptAsync());
        btnClose.addActionListener(e -> dispose());

        tblNotifications.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectedNotificationDetails();
            }
        });

        if (walletService != null && userId != null && !userId.trim().isEmpty()) {
            loadNotificationsAsync();
        }
    }

    public CompletableFuture<List<Notification>> loadNotificationsAsync() {
        CompletableFuture<List<Notification>> future = new CompletableFuture<>();
        if (walletService == null || userId == null || userId.trim().isEmpty()) {
            future.complete(Collections.emptyList());
            return future;
        }
        boolean unreadOnly = chkUnreadOnly.isSelected();
        setControlsEnabled(false);
        lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.INFO));
        lblInboxFeedback.setText("Loading notifications...");

        new SwingWorker<InboxSnapshot, Void>() {
            @Override
            protected InboxSnapshot doInBackground() {
                lastLoadExecutedOffEdt = !SwingUtilities.isEventDispatchThread();
                SessionManager.getInstance().touchActivity();
                List<Notification> list = walletService.getNotifications(userId, unreadOnly);
                int unreadCount = walletService.getUnreadNotificationCount(userId);
                return new InboxSnapshot(list, unreadCount);
            }

            @Override
            protected void done() {
                setControlsEnabled(true);
                try {
                    InboxSnapshot snapshot = get();
                    populateTable(snapshot.notifications());
                    lblUnreadSummary.setText("Unread notifications: " + snapshot.unreadCount());
                    if (onUnreadCountChanged != null) {
                        onUnreadCountChanged.accept(snapshot.unreadCount());
                    }
                    lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
                    lblInboxFeedback.setText(snapshot.notifications().isEmpty()
                            ? (unreadOnly ? "No unread notifications." : "Your notification inbox is empty.")
                            : "Showing " + snapshot.notifications().size() + " notification(s).");
                    future.complete(snapshot.notifications());
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblInboxFeedback.setText("Failed to load notifications: " + msg);
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<Void> markSelectedReadAsync(boolean markAsRead) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Notification selected = getSelectedNotification();
        if (selected == null || walletService == null) {
            future.complete(null);
            return future;
        }
        setControlsEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                SessionManager.getInstance().touchActivity();
                if (markAsRead) {
                    walletService.markNotificationRead(userId, selected.getNotificationId());
                } else {
                    walletService.markNotificationUnread(userId, selected.getNotificationId());
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadNotificationsAsync().whenComplete((list, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrapCause(err));
                        } else {
                            future.complete(null);
                        }
                    });
                } catch (Exception ex) {
                    setControlsEnabled(true);
                    Throwable cause = unwrapCause(ex);
                    lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblInboxFeedback.setText("Update failed: " + cause.getMessage());
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    public CompletableFuture<Integer> markAllReadAsync() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        if (walletService == null || userId == null) {
            future.complete(0);
            return future;
        }
        setControlsEnabled(false);
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                SessionManager.getInstance().touchActivity();
                return walletService.markAllNotificationsRead(userId);
            }

            @Override
            protected void done() {
                try {
                    int updated = get();
                    loadNotificationsAsync().whenComplete((list, err) -> {
                        if (err != null) {
                            future.completeExceptionally(unwrapCause(err));
                        } else {
                            lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.SUCCESS));
                            lblInboxFeedback.setText("Marked " + updated + " notification(s) as read.");
                            future.complete(updated);
                        }
                    });
                } catch (Exception ex) {
                    setControlsEnabled(true);
                    Throwable cause = unwrapCause(ex);
                    lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblInboxFeedback.setText("Mark all read failed: " + cause.getMessage());
                    future.completeExceptionally(cause);
                }
            }
        }.execute();
        return future;
    }

    private void openLinkedReceiptAsync() {
        Notification selected = getSelectedNotification();
        if (selected == null || walletService == null || walletId == null) {
            return;
        }
        String lookupKey = selected.getReferenceNumber() != null && !selected.getReferenceNumber().trim().isEmpty()
                ? selected.getReferenceNumber()
                : selected.getTransactionId();
        if (lookupKey == null || lookupKey.trim().isEmpty()) {
            lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.WARNING));
            lblInboxFeedback.setText("Selected notification has no linked transaction receipt.");
            return;
        }
        setControlsEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                SessionManager.getInstance().touchActivity();
                if (!selected.isRead()) {
                    walletService.markNotificationRead(userId, selected.getNotificationId());
                }
                return walletService.getTransactionReceipt(walletId, lookupKey);
            }

            @Override
            protected void done() {
                setControlsEnabled(true);
                try {
                    String receipt = get();
                    loadNotificationsAsync();
                    JTextArea area = new JTextArea(receipt);
                    area.setEditable(false);
                    area.setFont(UiTheme.font(UiTheme.FontRole.MONO));
                    area.setMargin(new Insets(8, 10, 8, 10));
                    JScrollPane scroll = new JScrollPane(area);
                    scroll.setPreferredSize(OtogConfig.getInstance().getUiDimension(
                            "ui.receipt.width", "ui.receipt.height", 460, 360));
                    JOptionPane.showMessageDialog(NotificationInboxDialog.this, scroll,
                            "Transaction Receipt — " + lookupKey, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = unwrapCause(ex);
                    lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.ERROR));
                    lblInboxFeedback.setText("Could not load receipt: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void populateTable(List<Notification> notifications) {
        this.loadedNotifications = notifications != null
                ? Collections.unmodifiableList(notifications)
                : Collections.emptyList();
        notificationsTableModel.setRowCount(0);
        for (Notification n : this.loadedNotifications) {
            String dateText = n.getCreatedAt() != null ? n.getCreatedAt().format(dateFormatter) : "-";
            notificationsTableModel.addRow(new Object[]{
                    n.isRead() ? "READ" : "UNREAD",
                    dateText,
                    n.getTitle() != null ? n.getTitle() : "-",
                    n.getReferenceNumber() != null ? n.getReferenceNumber() : "-",
                    n.getMessage() != null ? n.getMessage() : ""
            });
        }
        updateSelectedNotificationDetails();
    }

    private void updateSelectedNotificationDetails() {
        Notification selected = getSelectedNotification();
        if (selected == null) {
            txtNotificationDetail.setText("Select a notification above to inspect its full details.");
            btnMarkRead.setEnabled(false);
            btnMarkUnread.setEnabled(false);
            btnViewLinkedReceipt.setEnabled(false);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Title:     ").append(selected.getTitle()).append("\n");
        sb.append("Status:    ").append(selected.isRead() ? "READ" : "UNREAD").append("\n");
        sb.append("Date:      ").append(selected.getCreatedAt() != null
                ? selected.getCreatedAt().format(dateFormatter) : "-").append("\n");
        if (selected.getReferenceNumber() != null) {
            sb.append("Reference: ").append(selected.getReferenceNumber()).append("\n");
        }
        sb.append("\n").append(selected.getMessage());
        txtNotificationDetail.setText(sb.toString());
        txtNotificationDetail.setCaretPosition(0);

        btnMarkRead.setEnabled(!selected.isRead());
        btnMarkUnread.setEnabled(selected.isRead());
        boolean hasReceiptLink = walletId != null
                && ((selected.getReferenceNumber() != null && !selected.getReferenceNumber().isEmpty())
                || (selected.getTransactionId() != null && !selected.getTransactionId().isEmpty()));
        btnViewLinkedReceipt.setEnabled(hasReceiptLink);
    }

    public Notification getSelectedNotification() {
        int viewRow = tblNotifications.getSelectedRow();
        int modelRow = viewRow >= 0 ? tblNotifications.convertRowIndexToModel(viewRow) : -1;
        if (modelRow < 0 || modelRow >= loadedNotifications.size()) {
            return null;
        }
        return loadedNotifications.get(modelRow);
    }

    private void setControlsEnabled(boolean enabled) {
        chkUnreadOnly.setEnabled(enabled);
        btnRefreshInbox.setEnabled(enabled);
        btnMarkAllRead.setEnabled(enabled);
        if (!enabled) {
            btnMarkRead.setEnabled(false);
            btnMarkUnread.setEnabled(false);
            btnViewLinkedReceipt.setEnabled(false);
        } else {
            updateSelectedNotificationDetails();
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

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTable getNotificationsTable() {
        return tblNotifications;
    }

    public JCheckBox getUnreadOnlyCheckBox() {
        return chkUnreadOnly;
    }

    public JButton getMarkReadButton() {
        return btnMarkRead;
    }

    public JButton getMarkUnreadButton() {
        return btnMarkUnread;
    }

    public JButton getMarkAllReadButton() {
        return btnMarkAllRead;
    }

    public JButton getViewLinkedReceiptButton() {
        return btnViewLinkedReceipt;
    }

    public JLabel getUnreadSummaryLabel() {
        return lblUnreadSummary;
    }

    public boolean isLastLoadExecutedOffEdt() {
        return lastLoadExecutedOffEdt;
    }

    private record InboxSnapshot(List<Notification> notifications, int unreadCount) {
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(5, 1, new Insets(12, 14, 12, 14), 8, 8));

        pnlTopBar = new JPanel();
        pnlTopBar.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 4, 0), 8, 0));
        pnlRoot.add(pnlTopBar, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblUnreadSummary = new JLabel("Unread notifications: 0");
        lblUnreadSummary.setFont(UiTheme.font(UiTheme.FontRole.SECTION_HEADER));
        pnlTopBar.add(lblUnreadSummary, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlTopBar.add(new Spacer(), new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        chkUnreadOnly = new JCheckBox("Unread Only");
        pnlTopBar.add(chkUnreadOnly, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnRefreshInbox = new JButton("Refresh");
        pnlTopBar.add(btnRefreshInbox, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        String[] columns = {"Status", "Date", "Title", "Reference", "Message"};
        notificationsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        tblNotifications = new JTable(notificationsTableModel);
        tblNotifications.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblNotifications.setRowHeight(OtogConfig.getInstance().getTableRowHeight());
        tblNotifications.getTableHeader().setReorderingAllowed(false);

        JScrollPane tableScroll = new JScrollPane(tblNotifications);
        pnlRoot.add(tableScroll, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                null, new Dimension(680, 210), null, 0, false));

        txtNotificationDetail = new JTextArea(5, 60);
        txtNotificationDetail.setEditable(false);
        txtNotificationDetail.setLineWrap(true);
        txtNotificationDetail.setWrapStyleWord(true);
        txtNotificationDetail.setFont(UiTheme.font(UiTheme.FontRole.MONO));
        txtNotificationDetail.setText("Select a notification above to inspect its full details.");
        JScrollPane detailScroll = new JScrollPane(txtNotificationDetail);
        detailScroll.setBorder(UiTheme.titledBorder("Notification Detail"));
        pnlRoot.add(detailScroll, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, new Dimension(680, 110), null, 0, false));

        pnlActions = new JPanel();
        pnlActions.setLayout(new GridLayoutManager(1, 6, new Insets(2, 0, 0, 0), 8, 0));
        pnlRoot.add(pnlActions, new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnMarkRead = new JButton("Mark Read");
        btnMarkRead.setEnabled(false);
        pnlActions.add(btnMarkRead, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnMarkUnread = new JButton("Mark Unread");
        btnMarkUnread.setEnabled(false);
        pnlActions.add(btnMarkUnread, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnMarkAllRead = new JButton("Mark All Read");
        pnlActions.add(btnMarkAllRead, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnViewLinkedReceipt = new JButton("View Receipt");
        btnViewLinkedReceipt.setEnabled(false);
        pnlActions.add(btnViewLinkedReceipt, new GridConstraints(0, 3, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlActions.add(new Spacer(), new GridConstraints(0, 4, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        btnClose = new JButton("Close");
        pnlActions.add(btnClose, new GridConstraints(0, 5, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblInboxFeedback = new JLabel(" ");
        lblInboxFeedback.setFont(UiTheme.font(UiTheme.FontRole.SMALL));
        lblInboxFeedback.setForeground(UiTheme.color(UiTheme.ColorRole.MUTED));
        pnlRoot.add(lblInboxFeedback, new GridConstraints(4, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
