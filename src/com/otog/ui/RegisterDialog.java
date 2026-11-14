package com.otog.ui;

import com.otog.model.User;
import com.otog.service.AuthService;

import javax.swing.*;
import java.awt.*;

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
        btnCancel = new JButton("Cancel");
        btnSubmit = new JButton("Register");

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnSubmit);
        add(buttonPanel, BorderLayout.SOUTH);

        btnCancel.addActionListener(e -> dispose());

        btnSubmit.addActionListener(e -> handleRegistration());
    }

    private void handleRegistration() {
        String name = txtName.getText().trim();
        String mobile = txtMobile.getText().trim();
        String email = txtEmail.getText().trim();
        String password = new String(txtPassword.getPassword());
        String confirmPassword = new String(txtConfirmPassword.getPassword());

        if (name.isEmpty() || mobile.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Name, Mobile Number, and Password are required.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!password.equals(confirmPassword)) {
            JOptionPane.showMessageDialog(this,
                    "Passwords do not match.",
                    "Validation Error",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            this.registeredUser = authService.registerUser(name, mobile, email, password);
            JOptionPane.showMessageDialog(this,
                    "Registration successful!\nA secure wallet has been created with 0.00 PHP balance.\nYou may now log in.",
                    "Registration Complete",
                    JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    ex.getMessage(),
                    "Registration Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    public User getRegisteredUser() {
        return registeredUser;
    }
}
