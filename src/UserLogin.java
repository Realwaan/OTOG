import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.otog.database.DatabaseManager;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.AuthService;
import com.otog.service.AuthServiceImpl;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import com.otog.ui.RegisterDialog;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class UserLogin extends JFrame {
    private JPanel pnlMain;
    private JTextField textFieldUsername;
    private JPasswordField passwordFieldPasswordInput;
    private JButton btnLogin;
    private JButton btnRegister;
    private JPanel pnlPassword;
    private JPanel pnl2FA;
    private JLabel twoFactorHeaderLabel;
    private JTextField textField2FA;
    private JButton btnVerify2FA;
    private JButton btnResendOtp;

    private final AuthService authService;
    private final WalletOperations walletService;
    private String currentAuthenticatedUserId;
    private String lastErrorMessage;
    private String lastIssuedOtp;
    private final Map<String, Integer> failedAttemptsByIdentifier = new HashMap<>();
    private User pendingUser;
    private Wallet pendingWallet;

    private static final class LoginOutcome {
        final boolean success;
        final User user;
        final Wallet wallet;
        final String errorMessage;
        final String identifierKey;
        final String issuedOtp;

        private LoginOutcome(boolean success, User user, Wallet wallet,
                             String errorMessage, String identifierKey, String issuedOtp) {
            this.success = success;
            this.user = user;
            this.wallet = wallet;
            this.errorMessage = errorMessage;
            this.identifierKey = identifierKey;
            this.issuedOtp = issuedOtp;
        }

        static LoginOutcome success(User user, Wallet wallet, String identifierKey, String issuedOtp) {
            return new LoginOutcome(true, user, wallet, null, identifierKey, issuedOtp);
        }

        static LoginOutcome failure(String errorMessage) {
            return new LoginOutcome(false, null, null, errorMessage, null, null);
        }
    }

    public UserLogin() {
        this((AuthService) null, (WalletOperations) null);
    }

    public UserLogin(AuthService authService, WalletService walletService) {
        this(authService, (WalletOperations) walletService);
    }

    public UserLogin(AuthService authService, WalletOperations walletService) {
        if (authService != null) {
            this.authService = authService;
            this.walletService = walletService;
        } else {
            DatabaseManager dbManager = new DatabaseManager();
            this.authService = new AuthServiceImpl(dbManager);
            this.walletService = new WalletService(dbManager, this.authService);
        }

        if (pnlMain == null) {
            $$$setupUI$$$();
        }

        pnlPassword.setVisible(true);
        pnl2FA.setVisible(false);

        btnLogin.addActionListener(e -> {
            String username = textFieldUsername.getText();
            String password = new String(passwordFieldPasswordInput.getPassword());

            if (username == null || username.trim().isEmpty() || password.isEmpty()) {
                applyLoginOutcome(LoginOutcome.failure("Please enter both username and password."));
                showLoginErrorDialog();
                return;
            }

            setLoginBusy(true);
            new SwingWorker<LoginOutcome, Void>() {
                @Override
                protected LoginOutcome doInBackground() {
                    return authenticateInBackground(username, password);
                }

                @Override
                protected void done() {
                    setLoginBusy(false);
                    try {
                        LoginOutcome outcome = get();
                        if (!applyLoginOutcome(outcome)) {
                            showLoginErrorDialog();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        applyLoginOutcome(LoginOutcome.failure("Login was interrupted. Please try again."));
                        showLoginErrorDialog();
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        applyLoginOutcome(LoginOutcome.failure(cause.getMessage()));
                        showLoginErrorDialog();
                    }
                }
            }.execute();
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
                btnVerify2FA.setEnabled(false);
                new SwingWorker<Boolean, Void>() {
                    @Override
                    protected Boolean doInBackground() {
                        return verifyTwoFactorCode(code);
                    }

                    @Override
                    protected void done() {
                        btnVerify2FA.setEnabled(true);
                        try {
                            if (get()) {
                                String displayName = (pendingUser != null && pendingUser.getName() != null)
                                        ? pendingUser.getName() : "User";
                                JOptionPane.showMessageDialog(
                                        pnlMain,
                                        "Two-Factor Authentication successful! Welcome, " + displayName + ".",
                                        "Success",
                                        JOptionPane.INFORMATION_MESSAGE
                                );
                                SwingUtilities.invokeLater(() -> {
                                    com.otog.ui.Main dashboard = new com.otog.ui.Main();
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
                                    Window ancestor = SwingUtilities.getWindowAncestor(pnlMain);
                                    if (ancestor != null) {
                                        ancestor.dispose();
                                    }
                                });
                            } else {
                                String msg = (lastErrorMessage != null && !lastErrorMessage.trim().isEmpty())
                                        ? lastErrorMessage
                                        : "Please enter a valid 2FA verification code.";
                                JOptionPane.showMessageDialog(
                                        pnlMain,
                                        msg,
                                        "2FA Error",
                                        JOptionPane.WARNING_MESSAGE
                                );
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            JOptionPane.showMessageDialog(
                                    pnlMain,
                                    cause.getMessage(),
                                    "2FA Error",
                                    JOptionPane.ERROR_MESSAGE
                            );
                        }
                    }
                }.execute();
            });
        }

        if (btnResendOtp != null) {
            btnResendOtp.addActionListener(e -> {
                if (currentAuthenticatedUserId == null || currentAuthenticatedUserId.trim().isEmpty()) {
                    return;
                }
                btnResendOtp.setEnabled(false);
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() {
                        return authService.issueTwoFactorOtp(currentAuthenticatedUserId);
                    }

                    @Override
                    protected void done() {
                        btnResendOtp.setEnabled(true);
                        try {
                            applySimulatedOtp(get());
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            lastErrorMessage = cause.getMessage();
                            JOptionPane.showMessageDialog(
                                    pnlMain,
                                    cause.getMessage(),
                                    "Resend OTP Error",
                                    JOptionPane.WARNING_MESSAGE
                            );
                        }
                    }
                }.execute();
            });
        }
    }

    private void setLoginBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        if (btnRegister != null) {
            btnRegister.setEnabled(!busy);
        }
        if (pnlMain != null) {
            pnlMain.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        }
    }

    private void showLoginErrorDialog() {
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

    public boolean attemptLogin(String username, String password) {
        LoginOutcome outcome = authenticateInBackground(username, password);
        return applyLoginOutcome(outcome);
    }

    private LoginOutcome authenticateInBackground(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            return LoginOutcome.failure("Please enter both username and password.");
        }

        String identifier = username.trim();
        String identifierKey = identifier.toLowerCase();

        if (authService == null) {
            return LoginOutcome.failure("Authentication service is unavailable.");
        }

        try {
            Optional<User> userOpt = authService.authenticatePassword(identifier, password);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                Wallet wallet = null;
                if (walletService != null) {
                    try {
                        wallet = walletService.getWalletByUserId(user.getUserId());
                    } catch (Exception walletEx) {
                        System.err.println("Wallet lookup failed for user " + user.getUserId() + ": " + walletEx.getMessage());
                    }
                }
                String issuedOtp = authService.issueTwoFactorOtp(user.getUserId());
                return LoginOutcome.success(user, wallet, identifierKey, issuedOtp);
            }

            int attempts = failedAttemptsByIdentifier.merge(identifierKey, 1, Integer::sum);
            if (attempts >= AuthServiceImpl.MAX_FAILED_ATTEMPTS) {
                return LoginOutcome.failure("Account locked: maximum failed login attempts ("
                        + AuthServiceImpl.MAX_FAILED_ATTEMPTS + ") reached.");
            }
            return LoginOutcome.failure("Wrong credentials! Attempt " + attempts + " of "
                    + AuthServiceImpl.MAX_FAILED_ATTEMPTS + ".");
        } catch (Exception ex) {
            String msg = ex.getMessage();
            if (msg != null && msg.contains("locked")) {
                failedAttemptsByIdentifier.put(identifierKey, AuthServiceImpl.MAX_FAILED_ATTEMPTS);
            }
            return LoginOutcome.failure(msg != null && !msg.trim().isEmpty()
                    ? msg
                    : "Authentication failed. Please try again.");
        }
    }

    private boolean applyLoginOutcome(LoginOutcome outcome) {
        if (outcome.success) {
            this.currentAuthenticatedUserId = outcome.user.getUserId();
            this.pendingUser = outcome.user;
            this.pendingWallet = outcome.wallet;
            if (outcome.identifierKey != null) {
                failedAttemptsByIdentifier.remove(outcome.identifierKey);
            }

            if (textField2FA != null) {
                textField2FA.setText("");
            }
            applySimulatedOtp(outcome.issuedOtp);
            pnlPassword.setVisible(false);
            pnl2FA.setVisible(true);
            pnlMain.revalidate();
            pnlMain.repaint();
            lastErrorMessage = null;
            return true;
        }

        lastErrorMessage = outcome.errorMessage;
        passwordFieldPasswordInput.setText("");
        return false;
    }

    public String resendTwoFactorOtp() {
        if (authService == null || currentAuthenticatedUserId == null || currentAuthenticatedUserId.trim().isEmpty()) {
            throw new IllegalStateException("No authenticated user pending 2FA.");
        }
        String newCode = authService.issueTwoFactorOtp(currentAuthenticatedUserId);
        applySimulatedOtp(newCode);
        return newCode;
    }

    private void applySimulatedOtp(String code) {
        this.lastIssuedOtp = code;
        this.lastErrorMessage = null;
        if (twoFactorHeaderLabel != null) {
            twoFactorHeaderLabel.setText(buildSimulatedOtpBanner(code));
        }
    }

    private String buildSimulatedOtpBanner(String code) {
        return "[Simulated SMS] OTP code: " + (code != null ? code : "") + " (valid 5 min, 1-time use):";
    }

    public boolean verifyTwoFactorCode(String code) {
        if (code == null || code.trim().isEmpty() || authService == null) {
            lastErrorMessage = "Please enter a valid 2FA verification code.";
            return false;
        }
        boolean verified = authService.verifyTwoFactorOtp(currentAuthenticatedUserId, code.trim());
        if (verified && pendingUser != null) {
            lastErrorMessage = null;
            SessionManager.getInstance().login(pendingUser, pendingWallet);
            return true;
        }
        lastErrorMessage = "Invalid, expired, reused, or attempt-limited 2FA code.";
        return false;
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

    public JButton getResendOtpButton() {
        return btnResendOtp;
    }

    public JLabel getTwoFactorHeaderLabel() {
        return twoFactorHeaderLabel;
    }

    public String getLastIssuedOtp() {
        return lastIssuedOtp;
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
        pnl2FA.setLayout(new GridLayoutManager(4, 2, new Insets(0, 0, 0, 0), -1, -1));
        pnl2FA.setVisible(false);
        pnlMain.add(pnl2FA, new GridConstraints(3, 0, 1, 3,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null, 0, false));

        twoFactorHeaderLabel = new JLabel("Login Successful! Complete Two-Factor Authentication (Simulated SMS):");
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

        btnResendOtp = new JButton("Resend Simulated OTP");
        pnl2FA.add(btnResendOtp, new GridConstraints(3, 0, 1, 2,
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
