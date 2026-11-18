package com.otog.ui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.otog.model.User;
import com.otog.service.AuthService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.concurrent.ExecutionException;

public class RegisterDialog extends JDialog {
    private final AuthService authService;
    private User registeredUser;

    private JPanel pnlRoot;
    private JPanel pnlForm;
    private JPanel pnlButtons;
    private JTextField txtName;
    private JTextField txtMobile;
    private JTextField txtEmail;
    private JPasswordField txtPassword;
    private JPasswordField txtConfirmPassword;
    private JButton btnSubmit;
    private JButton btnCancel;

    public RegisterDialog(Frame parent, AuthService authService) {
        super(parent, "Register New Account - OTOG E-Wallet", true);
        this.authService = authService;
        if (pnlRoot == null) {
            $$$setupUI$$$();
        }
        initUI();
    }

    private void initUI() {
        setContentPane(pnlRoot);
        setSize(480, 350);
        setLocationRelativeTo(getParent());
        setResizable(false);

        getRootPane().setDefaultButton(btnSubmit);
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
        btnSubmit.addActionListener(e -> handleRegistration());
    }

    private void handleRegistration() {
        if (authService == null) {
            showRegistrationError("Registration service is unavailable.");
            return;
        }
        String name = txtName.getText().trim();
        String mobile = txtMobile.getText().trim();
        String email = txtEmail.getText().trim();
        String password = new String(txtPassword.getPassword());
        String confirmPassword = new String(txtConfirmPassword.getPassword());

        if (name.isEmpty() || mobile.isEmpty() || password.isEmpty()) {
            showDialogMessage("Validation Error",
                    "Name, Mobile Number, and Password are required.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (password.trim().length() < 6) {
            showDialogMessage("Validation Error",
                    "Password must be at least 6 characters long.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!password.equals(confirmPassword)) {
            showDialogMessage("Validation Error",
                    "Passwords do not match.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        setBusy(true);
        // Password hashing, duplicate checks and the atomic user+wallet insert run off the EDT.
        new SwingWorker<User, Void>() {
            @Override
            protected User doInBackground() {
                return authService.registerUser(name, mobile, email, password);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    registeredUser = get();
                    showDialogMessage("Registration Complete",
                            "Registration successful!\nA secure wallet has been created with 0.00 PHP balance.\nYou may now log in.",
                            JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showRegistrationError("Registration was interrupted. Please try again.");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    showRegistrationError(cause.getMessage());
                }
            }
        }.execute();
    }

    private void showRegistrationError(String message) {
        showDialogMessage("Registration Failed",
                (message != null && !message.trim().isEmpty()) ? message : "Registration failed. Please try again.",
                JOptionPane.ERROR_MESSAGE);
    }

    protected void showDialogMessage(String title, String message, int messageType) {
        JOptionPane.showMessageDialog(this, message, title, messageType);
    }

    private void setBusy(boolean busy) {
        btnSubmit.setEnabled(!busy);
        btnSubmit.setText(busy ? "Registering..." : "Register");
        btnCancel.setEnabled(!busy);
        setDefaultCloseOperation(busy ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    public User getRegisteredUser() {
        return registeredUser;
    }

    public JPanel getRootPanel() {
        return pnlRoot;
    }

    public JTextField getNameField() {
        return txtName;
    }

    public JTextField getMobileField() {
        return txtMobile;
    }

    public JTextField getEmailField() {
        return txtEmail;
    }

    public JPasswordField getPasswordField() {
        return txtPassword;
    }

    public JPasswordField getConfirmPasswordField() {
        return txtConfirmPassword;
    }

    public JButton getSubmitButton() {
        return btnSubmit;
    }

    public JButton getCancelButton() {
        return btnCancel;
    }

    private void $$$setupUI$$$() {
        pnlRoot = new JPanel();
        pnlRoot.setLayout(new GridLayoutManager(3, 1, new Insets(16, 20, 14, 20), 10, 10));

        JLabel regHeader = new JLabel("Create Your OTOG E-Wallet Account");
        regHeader.setFont(new Font("SansSerif", Font.BOLD, 14));
        pnlRoot.add(regHeader, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlForm = new JPanel();
        pnlForm.setLayout(new GridLayoutManager(5, 2, new Insets(10, 12, 10, 12), 10, 10));
        pnlForm.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Account Details"));
        pnlRoot.add(pnlForm, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        pnlForm.add(new JLabel("Full Name:"), new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtName = new JTextField();
        pnlForm.add(txtName, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(200, -1), null, 0, false));

        pnlForm.add(new JLabel("Mobile Number (e.g. 09171234567):"), new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtMobile = new JTextField();
        pnlForm.add(txtMobile, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(200, -1), null, 0, false));

        pnlForm.add(new JLabel("Email Address (Optional):"), new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtEmail = new JTextField();
        pnlForm.add(txtEmail, new GridConstraints(2, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(200, -1), null, 0, false));

        pnlForm.add(new JLabel("Password (min 6 chars):"), new GridConstraints(3, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtPassword = new JPasswordField();
        pnlForm.add(txtPassword, new GridConstraints(3, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(200, -1), null, 0, false));

        pnlForm.add(new JLabel("Confirm Password:"), new GridConstraints(4, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
        txtConfirmPassword = new JPasswordField();
        pnlForm.add(txtConfirmPassword, new GridConstraints(4, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED,
                null, new Dimension(200, -1), null, 0, false));

        pnlButtons = new JPanel();
        pnlButtons.setLayout(new GridLayoutManager(1, 3, new Insets(4, 0, 0, 0), 10, 0));
        pnlRoot.add(pnlButtons, new GridConstraints(2, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnlButtons.add(new Spacer(), new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, 1,
                null, null, null, 0, false));

        btnCancel = new JButton("Back to Login");
        pnlButtons.add(btnCancel, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnSubmit = new JButton("Register");
        pnlButtons.add(btnSubmit, new GridConstraints(0, 2, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlRoot;
    }
}
