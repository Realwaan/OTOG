import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;

import javax.swing.*;
import java.awt.*;

public class UserLogin extends JFrame {
    private JPanel pnlMain;
    private JTextField textFieldUsername;
    private JPasswordField passwordFieldPasswordInput;
    private JButton btnLogin;
    private JPanel pnlPassword;
    private JPanel pnl2FA;
    private JTextField textField2FA;
    private JButton btnVerify2FA;

    public UserLogin() {
        if (pnlMain == null) {
            $$$setupUI$$$();
        }

        pnlPassword.setVisible(true);
        pnl2FA.setVisible(false);

        btnLogin.addActionListener(e -> {
            String username = textFieldUsername.getText();
            String password = new String(passwordFieldPasswordInput.getPassword());

            if (!attemptLogin(username, password)) {
                JOptionPane.showMessageDialog(
                        pnlMain,
                        "Wrong credentials! Please try again.",
                        "Login Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });

        if (btnVerify2FA != null) {
            btnVerify2FA.addActionListener(e -> {
                String code = textField2FA.getText().trim();
                if (verifyTwoFactorCode(code)) {
                    JOptionPane.showMessageDialog(
                            pnlMain,
                            "Two-Factor Authentication successful! Welcome, admin.",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } else {
                    JOptionPane.showMessageDialog(
                            pnlMain,
                            "Please enter your 2FA verification code.",
                            "2FA Error",
                            JOptionPane.WARNING_MESSAGE
                    );
                }
            });
        }
    }

    public boolean attemptLogin(String username, String password) {
        if ("admin".equals(username) && "password".equals(password)) {
            pnlPassword.setVisible(false);
            pnl2FA.setVisible(true);

            pnlMain.revalidate();
            pnlMain.repaint();
            return true;
        }
        passwordFieldPasswordInput.setText("");
        return false;
    }

    public boolean verifyTwoFactorCode(String code) {
        return code != null && !code.trim().isEmpty();
    }

    public JPanel getMainPanel() {
        return pnlMain;
    }

    public JPanel getPasswordPanel() {
        return pnlPassword;
    }

    public JPanel getTwoFactorPanel() {
        return pnl2FA;
    }

    public JTextField getUsernameField() {
        return textFieldUsername;
    }

    public JPasswordField getPasswordField() {
        return passwordFieldPasswordInput;
    }

    public JButton getLoginButton() {
        return btnLogin;
    }

    public JTextField getTwoFactorField() {
        return textField2FA;
    }

    public JButton getVerify2FAButton() {
        return btnVerify2FA;
    }

    private void $$$setupUI$$$() {
        pnlMain = new JPanel();
        pnlMain.setLayout(new GridLayoutManager(4, 3, new Insets(15, 15, 15, 15), -1, -1));
        pnlMain.setVisible(true);

        pnlPassword = new JPanel();
        pnlPassword.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
        pnlMain.add(pnlPassword, new GridConstraints(0, 0, 3, 3,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        JLabel usernameLabel = new JLabel("Username:");
        pnlPassword.add(usernameLabel, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        textFieldUsername = new JTextField();
        pnlPassword.add(textFieldUsername, new GridConstraints(0, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        JLabel passwordLabel = new JLabel("Password:");
        pnlPassword.add(passwordLabel, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        passwordFieldPasswordInput = new JPasswordField();
        passwordFieldPasswordInput.setEchoChar('*');
        pnlPassword.add(passwordFieldPasswordInput, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnLogin = new JButton("Login");
        pnlPassword.add(btnLogin, new GridConstraints(2, 0, 1, 2,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        pnl2FA = new JPanel();
        pnl2FA.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
        pnl2FA.setVisible(false);
        pnlMain.add(pnl2FA, new GridConstraints(3, 0, 1, 3,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        JLabel twoFactorHeaderLabel = new JLabel("Login Successful! Complete Two-Factor Authentication:");
        pnl2FA.add(twoFactorHeaderLabel, new GridConstraints(0, 0, 1, 2,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        JLabel twoFactorCodeLabel = new JLabel("2FA Code:");
        pnl2FA.add(twoFactorCodeLabel, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        textField2FA = new JTextField();
        pnl2FA.add(textField2FA, new GridConstraints(1, 1, 1, 1,
                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));

        btnVerify2FA = new JButton("Verify");
        pnl2FA.add(btnVerify2FA, new GridConstraints(2, 0, 1, 2,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED,
                null, null, null, 0, false));
    }

    public JComponent $$$getRootComponent$$$() {
        return pnlMain;
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if ("--verify".equals(arg)) {
                UserLogin app = new UserLogin();
                if (app.getMainPanel() == null) {
                    throw new IllegalStateException("UserLogin main panel failed to initialize.");
                }
                System.out.println("UserLogin initialized successfully.");
                app.dispose();
                return;
            }
        }

        SwingUtilities.invokeLater(() -> {
            UserLogin app = new UserLogin();

            JFrame frame = new JFrame("User Login");
            frame.setContentPane(app.pnlMain);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(750, 380);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);
        });
    }
}
