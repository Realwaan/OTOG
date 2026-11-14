import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.exception.EntityNotFoundException;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.RegisterDialog;
import com.otog.util.MoneyUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

public class UserLogin extends JFrame {
    private JPanel pnlMain;
    private JTextField textFieldUsername;
    private JPasswordField passwordFieldPasswordInput;
    private JButton btnLogin;
    private JButton btnRegister;
    private JPanel pnlPassword;
    private JPanel pnl2FA;
    private JTextField textField2FA;
    private JButton btnVerify2FA;

    private AuthService authService;
    private WalletService walletService;
    private String currentAuthenticatedUserId;
    private String lastErrorMessage;
    private int consecutiveFailedAttempts = 0;
    private User pendingUser;
    private Wallet pendingWallet;

    public UserLogin() {
        this(null, null);
    }

    public UserLogin(AuthService authService, WalletService walletService) {
        if (authService != null) {
            this.authService = authService;
            this.walletService = walletService;
        } else {
            DatabaseManager dbManager = new DatabaseManager();
            this.authService = new AuthServiceImpl(dbManager);
            this.walletService = new WalletService(dbManager);
        }

        if (pnlMain == null) {
            $$$setupUI$$$();
        }

        pnlPassword.setVisible(true);
        pnl2FA.setVisible(false);

        btnLogin.addActionListener(e -> {
            String username = textFieldUsername.getText();
            String password = new String(passwordFieldPasswordInput.getPassword());

            if (!attemptLogin(username, password)) {
                String errorMsg = (lastErrorMessage != null && !lastErrorMessage.trim().isEmpty())
                        ? lastErrorMessage
                        : "Wrong credentials! Please try again.";
                JOptionPane.showMessageDialog(
                        pnlMain,
                        errorMsg,
                        "Login Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });

        if (btnRegister != null) {
            btnRegister.addActionListener(e -> {
                RegisterDialog dialog = new RegisterDialog(this, this.authService);
                dialog.setVisible(true);
                User registered = dialog.getRegisteredUser();
                if (registered != null) {
                    textFieldUsername.setText(registered.getMobileNumber());
                    passwordFieldPasswordInput.setText("");
                    passwordFieldPasswordInput.requestFocusInWindow();
                }
            });
        }

        if (btnVerify2FA != null) {
            btnVerify2FA.addActionListener(e -> {
                String code = textField2FA.getText().trim();
                if (verifyTwoFactorCode(code)) {
                    SwingUtilities.invokeLater(this::openDashboard);
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

    public com.otog.ui.Main openDashboard() {
        WalletOperations service = (this.walletService != null)
                ? this.walletService
                : new WalletService(new DatabaseManager());
        com.otog.ui.Main dashboard = new com.otog.ui.Main(service);
        dashboard.setOnLogoutCallback(() -> {
            JFrame frame = new JFrame("User Login - OTOG E-Wallet");
            UserLogin fresh = new UserLogin();
            frame.setContentPane(fresh.getMainPanel());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(750, 420);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);
        });
        dashboard.setVisible(true);
        Window loginWindow = SwingUtilities.getWindowAncestor(pnlMain);
        if (loginWindow != null) {
            loginWindow.dispose();
        } else {
            dispose();
        }
        return dashboard;
    }

    public boolean attemptLogin(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            lastErrorMessage = "Please enter both username and password.";
            passwordFieldPasswordInput.setText("");
            return false;
        }

        if (consecutiveFailedAttempts >= 5) {
            lastErrorMessage = "Account locked: maximum failed login attempts (5) reached.";
            passwordFieldPasswordInput.setText("");
            return false;
        }

        if (authService != null) {
            try {
                Optional<User> userOpt = authService.authenticatePassword(username.trim(), password);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    this.currentAuthenticatedUserId = user.getUserId();
                    this.consecutiveFailedAttempts = 0;

                    Wallet wallet = null;
                    if (walletService != null) {
                        try {
                            wallet = walletService.getWalletByUserId(user.getUserId());
                        } catch (EntityNotFoundException notFound) {
                            wallet = walletService.createWallet(
                                    user.getUserId(), MoneyUtil.ZERO, MoneyUtil.DEFAULT_CURRENCY);
                        } catch (Exception ignored) {
                        }
                    }

                    this.pendingUser = user;
                    this.pendingWallet = wallet;
                    authService.issueTwoFactorOtp(user.getUserId());

                    pnlPassword.setVisible(false);
                    pnl2FA.setVisible(true);

                    pnlMain.revalidate();
                    pnlMain.repaint();
                    lastErrorMessage = null;
                    return true;
                } else {
                    consecutiveFailedAttempts++;
                    if (consecutiveFailedAttempts >= 5) {
                        lastErrorMessage = "Account locked: maximum failed login attempts (5) reached.";
                    } else {
                        lastErrorMessage = "Wrong credentials! Attempt " + consecutiveFailedAttempts + " of 5.";
                    }
                }
            } catch (Exception ex) {
                consecutiveFailedAttempts++;
                if (consecutiveFailedAttempts >= 5 || (ex.getMessage() != null && ex.getMessage().contains("locked"))) {
                    lastErrorMessage = "Account locked: maximum failed login attempts (5) reached.";
                } else {
                    lastErrorMessage = ex.getMessage();
                }
            }
        } else {
            // Fallback for isolated mocks
            if ("admin".equals(username) && "password".equals(password)) {
                this.consecutiveFailedAttempts = 0;
                this.pendingUser = null;
                this.pendingWallet = null;
                pnlPassword.setVisible(false);
                pnl2FA.setVisible(true);
                pnlMain.revalidate();
                pnlMain.repaint();
                lastErrorMessage = null;
                return true;
            }
            consecutiveFailedAttempts++;
            if (consecutiveFailedAttempts >= 5) {
                lastErrorMessage = "Account locked: maximum failed login attempts (5) reached.";
            } else {
                lastErrorMessage = "Wrong credentials! Attempt " + consecutiveFailedAttempts + " of 5.";
            }
        }

        passwordFieldPasswordInput.setText("");
        return false;
    }

    public boolean verifyTwoFactorCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }
        if (authService != null) {
            boolean verified = authService.verifyTwoFactorOtp(currentAuthenticatedUserId, code.trim());
            if (verified) {
                SessionManager.getInstance().login(pendingUser, pendingWallet);
            }
            return verified;
        }
        return true;
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

    public JButton getRegisterButton() {
        return btnRegister;
    }

    public JTextField getTwoFactorField() {
        return textField2FA;
    }

    public JButton getVerify2FAButton() {
        return btnVerify2FA;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private void $$$setupUI$$$() {
        pnlMain = new JPanel();
        pnlMain.setLayout(new GridLayoutManager(4, 3, new Insets(15, 15, 15, 15), -1, -1));
        pnlMain.setVisible(true);

        pnlPassword = new JPanel();
        pnlPassword.setLayout(new GridLayoutManager(4, 2, new Insets(0, 0, 0, 0), -1, -1));
        pnlMain.add(pnlPassword, new GridConstraints(0, 0, 3, 3,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        JLabel usernameLabel = new JLabel("Username / Mobile / Email:");
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

        btnRegister = new JButton("Create New Account / Register");
        pnlPassword.add(btnRegister, new GridConstraints(3, 0, 1, 2,
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

            JFrame frame = new JFrame("User Login - OTOG E-Wallet");
            frame.setContentPane(app.pnlMain);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(750, 420);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);
        });
    }
}
