package com.otog.ui;

import com.otog.model.User;
import com.otog.service.AuthService;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.ExecutionException;

public class RegisterDialog extends JDialog {
    private final AuthService authService;
    private User registeredUser;

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
        initUI();
    }

    private void initUI() {
        setSize(450, 320);
        setLocationRelativeTo(getParent());
        setResizable(false);
        setLayout(new BorderLayout(10, 10));

        JPanel formPanel = new JPanel(new GridLayout(5, 2, 8, 10));
        formPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 10, 20));

        formPanel.add(new JLabel("Full Name:"));
        txtName = new JTextField();
        formPanel.add(txtName);

        formPanel.add(new JLabel("Mobile Number (e.g. 09171234567):"));
        txtMobile = new JTextField();
        formPanel.add(txtMobile);

        formPanel.add(new JLabel("Email Address (Optional):"));
        txtEmail = new JTextField();
        formPanel.add(txtEmail);

        formPanel.add(new JLabel("Password (min 6 chars):"));
        txtPassword = new JPasswordField();
        formPanel.add(txtPassword);

        formPanel.add(new JLabel("Confirm Password:"));
        txtConfirmPassword = new JPasswordField();
        formPanel.add(txtConfirmPassword);

        add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        btnCancel = new JButton("Back to Login");
        btnSubmit = new JButton("Register");

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnSubmit);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(btnSubmit);
        getRootPane().registerKeyboardAction(
                e -> {
                    if (btnCancel.isEnabled()) {
                        dispose();
                    }
                },
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
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
}
