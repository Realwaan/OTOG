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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private JButton btnBackToLogin;

    private final AuthService authService;
    private final WalletOperations walletService;
    private final DatabaseManager ownedDatabaseManager;
    private boolean dashboardOpened;
    private final Map<String, Integer> failedAttemptsByIdentifier = new ConcurrentHashMap<>();

    // Written via EDT outcome application and read from tests / background checks.
    private volatile String currentAuthenticatedUserId;
    private volatile String lastErrorMessage;
    private volatile User pendingUser;
    private volatile Wallet pendingWallet;
    private volatile DatabaseManager preparedDashboardDatabase;
    private volatile String lastIssuedOtp;

    private static final class LoginOutcome {
        final boolean success;
        final User user;
        final Wallet wallet;
        final String errorMessage;
        final String identifierKey;
        final String issuedOtp;

        private LoginOutcome(boolean success, User user, Wallet wallet, String errorMessage,
                             String identifierKey, String issuedOtp) {
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
            this.ownedDatabaseManager = null;
        } else {
            this.ownedDatabaseManager = new DatabaseManager();
            this.authService = new AuthServiceImpl(this.ownedDatabaseManager);
            this.walletService = new WalletService(this.ownedDatabaseManager, this.authService);
        }

        if (pnlMain == null) {
            $$$setupUI$$$();
        }

        pnlPassword.setVisible(true);
        pnl2FA.setVisible(false);

        btnLogin.addActionListener(e -> submitLoginAsync());
        if (textFieldUsername != null) {
            textFieldUsername.addActionListener(e -> {
                if (passwordFieldPasswordInput != null) {
                    passwordFieldPasswordInput.requestFocusInWindow();
                }
            });
        }
        if (passwordFieldPasswordInput != null) {
            passwordFieldPasswordInput.addActionListener(e -> submitLoginAsync());
        }

        if (btnRegister != null) {
            btnRegister.addActionListener(e -> {
                if (this.authService == null) {
                    showDialogMessage("Registration Error",
                            "Registration service is unavailable.",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
                RegisterDialog dialog = new RegisterDialog(resolveOwnerFrame(), this.authService);
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
            btnVerify2FA.addActionListener(e -> submitTwoFactorAsync());
        }
        if (textField2FA != null) {
            textField2FA.addActionListener(e -> submitTwoFactorAsync());
        }

        if (btnResendOtp != null) {
            btnResendOtp.addActionListener(e -> submitResendOtpAsync());
        }

        if (btnBackToLogin != null) {
            btnBackToLogin.addActionListener(e -> returnToPasswordScreen());
        }
    }

    private Frame resolveOwnerFrame() {
        Window ancestor = pnlMain != null ? SwingUtilities.getWindowAncestor(pnlMain) : null;
        return (ancestor instanceof Frame frame) ? frame : this;
    }

    public void returnToPasswordScreen() {
        this.currentAuthenticatedUserId = null;
        this.pendingUser = null;
        this.pendingWallet = null;
        this.lastIssuedOtp = null;
        this.lastErrorMessage = null;
        if (textField2FA != null) {
            textField2FA.setText("");
        }
        if (twoFactorHeaderLabel != null) {
            twoFactorHeaderLabel.setText("Login Successful! Complete Two-Factor Authentication (Simulated SMS):");
        }
        if (passwordFieldPasswordInput != null) {
            passwordFieldPasswordInput.setText("");
        }
        if (pnl2FA != null) {
            pnl2FA.setVisible(false);
        }
        if (pnlPassword != null) {
            pnlPassword.setVisible(true);
        }
        setLoginInputsBusy(false);
        if (pnlMain != null) {
            pnlMain.revalidate();
            pnlMain.repaint();
        }
        if (textFieldUsername != null) {
            textFieldUsername.requestFocusInWindow();
        }
    }

    protected void showDialogMessage(String title, String message, int messageType) {
        JOptionPane.showMessageDialog(pnlMain, message, title, messageType);
    }

    /**
     * Runs password hashing, SQLite lookups, wallet provisioning and OTP issuance on a
     * SwingWorker background thread; only UI state changes happen on the EDT in done().
     */
    private void submitLoginAsync() {
        final String username = textFieldUsername.getText();
        final String password = new String(passwordFieldPasswordInput.getPassword());
        if (username == null || username.trim().isEmpty() || password.isEmpty()) {
            applyLoginOutcome(LoginOutcome.failure("Please enter both username and password."));
            showLoginErrorDialog();
            return;
        }
        setLoginInputsBusy(true);

        new SwingWorker<LoginOutcome, Void>() {
            @Override
            protected LoginOutcome doInBackground() {
                return authenticateInBackground(username, password);
            }

            @Override
            protected void done() {
                LoginOutcome outcome;
                try {
                    outcome = get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    outcome = LoginOutcome.failure("Login was interrupted. Please try again.");
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    outcome = LoginOutcome.failure(cause.getMessage());
                }
                setLoginInputsBusy(false);
                if (!applyLoginOutcome(outcome)) {
                    showLoginErrorDialog();
                }
            }
        }.execute();
    }

    private void showLoginErrorDialog() {
        String errorMsg = (lastErrorMessage != null && !lastErrorMessage.trim().isEmpty())
                ? lastErrorMessage
                : "Wrong credentials! Please try again.";
        showDialogMessage("Login Error", errorMsg, JOptionPane.ERROR_MESSAGE);
    }

    /** Verifies the OTP and starts the session off the EDT, then opens the dashboard on the EDT. */
    private void submitTwoFactorAsync() {
        final String code = textField2FA.getText().trim();
        if (code.isEmpty()) {
            showDialogMessage("2FA Error",
                    "Please enter your 2FA verification code.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        setTwoFactorInputsBusy(true);

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                boolean verified = verifyTwoFactorCode(code);
                if (verified && walletService == null) {
                    // Bootstrap schema off the EDT; the dashboard takes ownership and closes it.
                    preparedDashboardDatabase = new DatabaseManager();
                }
                return verified;
            }

            @Override
            protected void done() {
                setTwoFactorInputsBusy(false);
                String failure = (lastErrorMessage != null && !lastErrorMessage.trim().isEmpty())
                        ? lastErrorMessage
                        : "Invalid, expired, reused, or attempt-limited 2FA code.";
                try {
                    if (get()) {
                        try {
                            openDashboard();
                            return;
                        } catch (Exception launchEx) {
                            failure = (launchEx.getMessage() != null && !launchEx.getMessage().trim().isEmpty())
                                    ? "Unable to open dashboard: " + launchEx.getMessage()
                                    : "Unable to open dashboard. Please try logging in again.";
                            showDialogMessage("Dashboard Error", failure, JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failure = "Verification was interrupted. Please try again.";
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
                        failure = cause.getMessage();
                    }
                }
                showDialogMessage("2FA Error", failure, JOptionPane.WARNING_MESSAGE);
            }
        }.execute();
    }

    private void submitResendOtpAsync() {
        final String userId = currentAuthenticatedUserId;
        if (authService == null || userId == null || userId.trim().isEmpty()) {
            showDialogMessage("Resend OTP Error",
                    "No authenticated user is currently pending 2FA.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (btnResendOtp != null) {
            btnResendOtp.setEnabled(false);
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return authService.issueTwoFactorOtp(userId);
            }

            @Override
            protected void done() {
                if (btnResendOtp != null) {
                    btnResendOtp.setEnabled(true);
                }
                try {
                    String newCode = get();
                    applySimulatedOtp(newCode);
                    if (newCode != null && pnlMain.isShowing()) {
                        showDialogMessage("Two-Factor Authentication",
                                "[Simulated SMS] A new 2FA code has been issued: " + newCode
                                        + "\n(Valid for 5 minutes, one-time use)",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showDialogMessage("Resend OTP Error",
                            "OTP resend was interrupted. Please try again.",
                            JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = (cause.getMessage() != null && !cause.getMessage().trim().isEmpty())
                            ? cause.getMessage()
                            : "Unable to resend OTP. Please try again.";
                    lastErrorMessage = msg;
                    showDialogMessage("Resend OTP Error", msg, JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void setLoginInputsBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnLogin.setText(busy ? "Signing in..." : "Login");
        if (btnRegister != null) {
            btnRegister.setEnabled(!busy);
        }
        textFieldUsername.setEnabled(!busy);
        passwordFieldPasswordInput.setEnabled(!busy);
        pnlMain.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void setTwoFactorInputsBusy(boolean busy) {
        btnVerify2FA.setEnabled(!busy);
        btnVerify2FA.setText(busy ? "Verifying..." : "Verify");
        if (btnResendOtp != null) {
            btnResendOtp.setEnabled(!busy);
        }
        if (btnBackToLogin != null) {
            btnBackToLogin.setEnabled(!busy);
        }
        textField2FA.setEnabled(!busy);
        pnlMain.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    public com.otog.ui.Main openDashboard() {
        com.otog.ui.Main dashboard;
        if (this.walletService != null) {
            dashboard = new com.otog.ui.Main(this.walletService, this.authService);
        } else {
            DatabaseManager ownedDb = (preparedDashboardDatabase != null)
                    ? preparedDashboardDatabase
                    : new DatabaseManager();
            preparedDashboardDatabase = null;
            // Ownership transfers to Main, which closes the database in dispose().
            dashboard = com.otog.ui.Main.withOwnedDatabase(ownedDb, this.authService);
        }
        final AuthService sharedAuthService = this.authService;
        final WalletOperations sharedWalletService = this.walletService;
        dashboard.setOnLogoutCallback(() -> {
            JFrame frame = new JFrame("User Login - OTOG E-Wallet");
            // Reuse the same service instances (and their database) instead of opening a new disk DB on the EDT.
            UserLogin fresh = new UserLogin(sharedAuthService, sharedWalletService);
            frame.setContentPane(fresh.getMainPanel());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(750, 420);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);
        });
        this.dashboardOpened = true;
        dashboard.setVisible(true);
        Window loginWindow = SwingUtilities.getWindowAncestor(pnlMain);
        if (loginWindow != null) {
            loginWindow.dispose();
        } else {
            dispose();
        }
        return dashboard;
    }

    @Override
    public void dispose() {
        if (!dashboardOpened) {
            if (preparedDashboardDatabase != null) {
                preparedDashboardDatabase.close();
                preparedDashboardDatabase = null;
            }
            if (ownedDatabaseManager != null) {
                ownedDatabaseManager.close();
            }
        }
        super.dispose();
    }

    /**
     * Synchronous login (used by tests and CLI verification): background work followed by UI updates.
     * Interactive clicks go through {@link #submitLoginAsync()} so this work never blocks the EDT.
     */
    public boolean attemptLogin(String username, String password) {
        LoginOutcome outcome = authenticateInBackground(username, password);
        return applyLoginOutcome(outcome);
    }

    /**
     * Pure non-UI login work: credential checks, PBKDF2 hashing, wallet lookup/provisioning and
     * OTP issuance. Must not touch Swing components so it is safe inside SwingWorker.doInBackground().
     */
    private LoginOutcome authenticateInBackground(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            return LoginOutcome.failure("Please enter both username and password.");
        }

        String identifier = username.trim();
        String identifierKey = identifier.toLowerCase(Locale.ROOT);

        if (failedAttemptsByIdentifier.getOrDefault(identifierKey, 0) >= AuthServiceImpl.MAX_FAILED_ATTEMPTS) {
            return LoginOutcome.failure("Account locked: maximum failed login attempts ("
                    + AuthServiceImpl.MAX_FAILED_ATTEMPTS + ") reached.");
        }

        if (authService != null) {
            try {
                Optional<User> userOpt = authService.authenticatePassword(identifier, password);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    Wallet wallet = null;
                    if (walletService != null) {
                        try {
                            try {
                                wallet = walletService.getWalletByUserId(user.getUserId());
                            } catch (EntityNotFoundException notFound) {
                                wallet = walletService.createWallet(
                                        user.getUserId(), MoneyUtil.ZERO, MoneyUtil.DEFAULT_CURRENCY);
                            }
                        } catch (RuntimeException walletFailure) {
                            // Credentials were correct; do not count this as a failed attempt.
                            return LoginOutcome.failure("Signed in, but your wallet could not be loaded: "
                                    + walletFailure.getMessage());
                        }
                    }

                    String issuedOtp = authService.issueTwoFactorOtp(user.getUserId());
                    return LoginOutcome.success(user, wallet, identifierKey, issuedOtp);
                }

                return recordFailedAttempt(identifierKey);
            } catch (Exception ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("locked")) {
                    failedAttemptsByIdentifier.put(identifierKey, AuthServiceImpl.MAX_FAILED_ATTEMPTS);
                    return LoginOutcome.failure(msg);
                }
                return LoginOutcome.failure(msg != null && !msg.trim().isEmpty()
                        ? msg
                        : "Authentication failed. Please try again.");
            }
        } else {
            return LoginOutcome.failure("Authentication service is unavailable.");
        }
    }

    private LoginOutcome recordFailedAttempt(String identifierKey) {
        int attempts = failedAttemptsByIdentifier.merge(identifierKey, 1, Integer::sum);
        if (attempts >= AuthServiceImpl.MAX_FAILED_ATTEMPTS) {
            return LoginOutcome.failure("Account locked: maximum failed login attempts ("
                    + AuthServiceImpl.MAX_FAILED_ATTEMPTS + ") reached.");
        }
        return LoginOutcome.failure("Wrong credentials! Attempt " + attempts + " of "
                + AuthServiceImpl.MAX_FAILED_ATTEMPTS + ".");
    }

    /** EDT-only UI transition after a login attempt. */
    private boolean applyLoginOutcome(LoginOutcome outcome) {
        if (outcome.success) {
            this.currentAuthenticatedUserId = outcome.user != null ? outcome.user.getUserId() : null;
            this.pendingUser = outcome.user;
            this.pendingWallet = outcome.wallet;
            this.lastErrorMessage = null;
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
            if (lastIssuedOtp != null && pnlMain.isShowing()) {
                showDialogMessage(
                        "Two-Factor Authentication",
                        "[Simulated SMS] Your 2FA verification code is: " + lastIssuedOtp
                                + "\n(Valid for 5 minutes, one-time use)",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
            textField2FA.requestFocusInWindow();
            passwordFieldPasswordInput.setText("");
            return true;
        }

        this.lastIssuedOtp = null;
        this.lastErrorMessage = outcome.errorMessage;
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
            lastIssuedOtp = null;
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

    public JButton getBackToLoginButton() {
        return btnBackToLogin;
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
        pnl2FA.setLayout(new GridLayoutManager(5, 2, new Insets(0, 0, 0, 0), -1, -1));
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

        btnBackToLogin = new JButton("Back to Login");
        pnl2FA.add(btnBackToLogin, new GridConstraints(4, 0, 1, 2,
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

        // Bootstrap the SQLite schema on the main thread, never on the EDT.
        final DatabaseManager dbManager;
        final AuthService authService;
        final WalletService walletService;
        try {
            dbManager = new DatabaseManager();
            authService = new AuthServiceImpl(dbManager);
            walletService = new WalletService(dbManager, authService);
        } catch (Exception ex) {
            String message = (ex.getMessage() != null && !ex.getMessage().trim().isEmpty())
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    "Unable to initialize OTOG E-Wallet:\n" + message,
                    "Startup Error",
                    JOptionPane.ERROR_MESSAGE
            ));
            return;
        }

        SwingUtilities.invokeLater(() -> {
            UserLogin app = new UserLogin(authService, walletService);

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
