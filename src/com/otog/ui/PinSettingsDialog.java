package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.service.AuthService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Bound IntelliJ Swing form dialog for setting or changing the user's 4-6 digit Transaction PIN.
 * Executes all PBKDF2/SHA-256 PIN hashing and database persistence off the Swing EDT via SwingWorker.
 */
public class PinSettingsDialog extends JDialog {

    @FunctionalInterface
    public interface DialogMessageCallback {
        void show(Component parent, String title, String message, int messageType);
    }

    private final AuthService authService;
    private final String userId;
    private final boolean hasExistingPin;
    private final DialogMessageCallback messageCallback;
    private final Consumer<String> errorInspector;
    private final Runnable onPinSavedCallback;

    private JPanel pnlRoot;
    private JPanel pnlForm;
    private JPanel pnlMode;
    private JPanel pnlButtons;
    private JRadioButton rbChangePin;
    private JRadioButton rbResetWithPassword;
    private JLabel lblSecret;
    private JPasswordField txtSecret;
    private JPasswordField txtNewPin;
    private JPasswordField txtConfirmPin;
    private JLabel lblPinDialogFeedback;
    private JButton btnCancel;
    private JButton btnSavePin;

    public PinSettingsDialog(Frame owner,
                             AuthService authService,
                             String userId,
                             boolean hasExistingPin,
                             DialogMessageCallback messageCallback,
                             Consumer<String> errorInspector,
                             Runnable onPinSavedCallback) {
        super(owner, hasExistingPin ? "Change Transaction PIN" : "Set Transaction PIN", true);
        this.authService = authService;
        this.userId = userId;
        this.hasExistingPin = hasExistingPin;
        this.messageCallback = messageCallback != null
                ? messageCallback
                : (parent, title, msg, type) -> JOptionPane.showMessageDialog(parent, msg, title, type);
        this.errorInspector = errorInspector;
        this.onPinSavedCallback = onPinSavedCallback;

        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        initUI();
    }

    private void initUI() {
        setContentPane(pnlRoot);
        setSize(440, 290);
        setLocationRelativeTo(getParent());
        setResizable(false);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(rbChangePin);
        modeGroup.add(rbResetWithPassword);

        rbChangePin.setSelected(hasExistingPin);
        rbChangePin.setEnabled(hasExistingPin);
        rbResetWithPassword.setSelected(!hasExistingPin);
        rbResetWithPassword.setText(hasExistingPin ? "Reset with Account Password" : "Verify with Account Password");
        pnlMode.setVisible(hasExistingPin);
        lblSecret.setText(hasExistingPin ? "Current PIN:" : "Account Password:");

        rbChangePin.addActionListener(e -> lblSecret.setText("Current PIN:"));
        rbResetWithPassword.addActionListener(e -> lblSecret.setText("Account Password:"));

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(btnSavePin);
        getRootPane().registerKeyboardAction(
                e -> {
                    if (btnCancel.isEnabled()) {
                        dispose();
                    }
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        btnCancel.addActionListener(e -> dispose());
        btnSavePin.addActionListener(e -> handleSavePin());
    }

    private void handleSavePin() {
        String secret = new String(txtSecret.getPassword());
        String newPin = new String(txtNewPin.getPassword()).trim();
        String confirmPin = new String(txtConfirmPin.getPassword()).trim();

        if (secret.isEmpty() || newPin.isEmpty()) {
            String msg = "Please fill in all fields.";
            lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
            lblPinDialogFeedback.setText(msg);
            messageCallback.show(this, "Validation Error", msg, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!newPin.equals(confirmPin)) {
            String msg = "New PIN and confirmation do not match.";
            lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
            lblPinDialogFeedback.setText(msg);
            messageCallback.show(this, "Validation Error", msg, JOptionPane.WARNING_MESSAGE);
            return;
        }

        final boolean useCurrentPin = rbChangePin.isSelected();
        btnSavePin.setEnabled(false);
        btnCancel.setEnabled(false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        lblPinDialogFeedback.setForeground(new Color(70, 80, 95));
        lblPinDialogFeedback.setText("Saving Transaction PIN...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                if (useCurrentPin) {
                    authService.changePin(userId, secret, newPin);
                } else {
                    authService.setPin(userId, secret, newPin);
                }
                return null;
            }

            @Override
            protected void done() {
                btnSavePin.setEnabled(true);
                btnCancel.setEnabled(true);
                setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                try {
                    get();
                    if (onPinSavedCallback != null) {
                        onPinSavedCallback.run();
                    }
                    messageCallback.show(PinSettingsDialog.this,
                            "Transaction PIN",
                            "Transaction PIN saved successfully.",
                            JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
                    lblPinDialogFeedback.setText("PIN update interrupted.");
                    messageCallback.show(PinSettingsDialog.this,
                            "Transaction PIN Error",
                            "PIN update interrupted.",
                            JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String errorMsg = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : cause.getClass().getSimpleName();
                    lblPinDialogFeedback.setForeground(new Color(176, 38, 38));
                    lblPinDialogFeedback.setText(errorMsg);
                    if (com.otog.service.SessionManager.getInstance().isExpiredDueToInactivity()
                            || com.otog.service.SessionManager.getInstance().isLockedOutByPin()) {
                        dispose();
                        if (errorInspector != null) {
                            errorInspector.accept(errorMsg);
                        }
                    } else {
                        messageCallback.show(PinSettingsDialog.this,
                                "Transaction PIN Error",
                                errorMsg,
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JPasswordField getSecretField() {
        return txtSecret;
    }

    public JPasswordField getNewPinField() {
        return txtNewPin;
    }

    public JPasswordField getConfirmPinField() {
        return txtConfirmPin;
    }

    public JButton getSavePinButton() {
        return btnSavePin;
    }

    public JButton getCancelButton() {
        return btnCancel;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(2, 1, new Insets(14, 18, 12, 18), 8, 10));

        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(5, 2, new Insets(10, 12, 10, 12), 8, 8));
        pnlForm.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Transaction PIN Security"));
        pnlRoot.add(pnlForm, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        pnlMode = new JPanel();
        pnlMode.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 4, 0), 10, 0));
        pnlForm.add(pnlMode, new GridConstraints(0, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        rbChangePin = new JRadioButton("Use Current PIN");
        pnlMode.add(rbChangePin, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        rbResetWithPassword = new JRadioButton("Reset with Account Password");
        pnlMode.add(rbResetWithPassword, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        lblSecret = new JLabel("Account Password:");
        pnlForm.add(lblSecret, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtSecret = new JPasswordField(18);
        pnlForm.add(txtSecret, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(180, -1), null, 0, false));

        pnlForm.add(new JLabel("New PIN (4-6 digits):"), new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtNewPin = new JPasswordField(18);
        pnlForm.add(txtNewPin, new GridConstraints(2, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(180, -1), null, 0, false));

        pnlForm.add(new JLabel("Confirm New PIN:"), new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        txtConfirmPin = new JPasswordField(18);
        pnlForm.add(txtConfirmPin, new GridConstraints(3, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(180, -1), null, 0, false));

        lblPinDialogFeedback = new JLabel(" ");
        lblPinDialogFeedback.setFont(new Font("SansSerif", Font.PLAIN, 11));
        pnlForm.add(lblPinDialogFeedback, new GridConstraints(4, 0, 1, 2,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlButtons = new JPanel();
        pnlButtons.setLayout(new GridLayoutManager(1, 3, new Insets(2, 0, 0, 0), 8, 0));
        pnlRoot.add(pnlButtons, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlButtons.add(new Spacer(), new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        btnCancel = new JButton("Cancel");
        pnlButtons.add(btnCancel, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSavePin = new JButton("Save PIN");
        pnlButtons.add(btnSavePin, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
