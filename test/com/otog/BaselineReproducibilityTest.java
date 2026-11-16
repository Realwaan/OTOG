package com.otog;

import com.otog.service.AuthService;
import com.otog.service.InvestmentService;
import com.otog.service.LoanService;
import com.otog.service.SessionManager;
import com.otog.service.WalletOperations;
import com.otog.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies CAP-01 clean-checkout reproducibility:
 * - UserLogin form components initialize cleanly without IntelliJ bytecode instrumentation.
 * - Login transition and 2FA verification logic work as expected.
 * - Shared service contracts are present and implemented by WalletService.
 */
public class BaselineReproducibilityTest {

    @AfterEach
    void resetSession() {
        SessionManager.getInstance().reset();
    }

    @Test
    void testUserLoginFormInitializesWithoutBytecodeInstrumentation() throws Exception {
        Class<?> loginClass = Class.forName("UserLogin");
        Object instance = loginClass.getDeclaredConstructor().newInstance();
        try {
            JPanel pnlMain = (JPanel) loginClass.getMethod("getMainPanel").invoke(instance);
            JPanel pnlPassword = (JPanel) loginClass.getMethod("getPasswordPanel").invoke(instance);
            JPanel pnl2FA = (JPanel) loginClass.getMethod("getTwoFactorPanel").invoke(instance);
            JTextField usernameField = (JTextField) loginClass.getMethod("getUsernameField").invoke(instance);
            JPasswordField passwordField = (JPasswordField) loginClass.getMethod("getPasswordField").invoke(instance);
            JButton loginButton = (JButton) loginClass.getMethod("getLoginButton").invoke(instance);
            JTextField twoFactorField = (JTextField) loginClass.getMethod("getTwoFactorField").invoke(instance);
            JButton verifyButton = (JButton) loginClass.getMethod("getVerify2FAButton").invoke(instance);

            assertNotNull(pnlMain, "pnlMain must be initialized");
            assertNotNull(pnlPassword, "pnlPassword must be initialized");
            assertNotNull(pnl2FA, "pnl2FA must be initialized");
            assertNotNull(usernameField, "textFieldUsername must be initialized");
            assertNotNull(passwordField, "passwordFieldPasswordInput must be initialized");
            assertNotNull(loginButton, "btnLogin must be initialized");
            assertNotNull(twoFactorField, "textField2FA must be initialized");
            assertNotNull(verifyButton, "btnVerify2FA must be initialized");

            assertSame(pnlMain, pnlPassword.getParent(), "pnlPassword must be attached to pnlMain");
            assertSame(pnlMain, pnl2FA.getParent(), "pnl2FA must be attached to pnlMain");
            assertTrue(pnlPassword.isVisible(), "Password panel should be visible initially");
            assertFalse(pnl2FA.isVisible(), "2FA panel should be hidden initially");
        } finally {
            if (instance instanceof JFrame frame) {
                frame.dispose();
            }
        }
    }

    @Test
    void testUserLoginTransitionAndTwoFactorValidation() throws Exception {
        Class<?> loginClass = Class.forName("UserLogin");
        Object instance = loginClass.getDeclaredConstructor().newInstance();
        try {
            Method attemptLogin = loginClass.getMethod("attemptLogin", String.class, String.class);
            Method verifyTwoFactorCode = loginClass.getMethod("verifyTwoFactorCode", String.class);
            JPanel pnlPassword = (JPanel) loginClass.getMethod("getPasswordPanel").invoke(instance);
            JPanel pnl2FA = (JPanel) loginClass.getMethod("getTwoFactorPanel").invoke(instance);

            assertFalse((Boolean) attemptLogin.invoke(instance, "wrong", "creds"));
            assertTrue(pnlPassword.isVisible(), "Invalid login must keep password panel visible");
            assertFalse(pnl2FA.isVisible(), "Invalid login must keep 2FA panel hidden");

            assertTrue((Boolean) attemptLogin.invoke(instance, "admin", "password"));
            assertFalse(pnlPassword.isVisible(), "Valid login must hide password panel");
            assertTrue(pnl2FA.isVisible(), "Valid login must show 2FA panel");

            JLabel headerLabel = (JLabel) loginClass.getMethod("getTwoFactorHeaderLabel").invoke(instance);
            assertNotNull(headerLabel);
            assertTrue(headerLabel.getText().contains("Simulated SMS"),
                    "OTP prompt must label delivery as simulated");
            String issuedOtp = (String) loginClass.getMethod("getLastIssuedOtp").invoke(instance);
            assertNotNull(issuedOtp, "Simulated OTP must be issued on login");
            String wrongCode = "123456".equals(issuedOtp) ? "654321" : "123456";

            assertFalse((Boolean) verifyTwoFactorCode.invoke(instance, ""));
            assertFalse((Boolean) verifyTwoFactorCode.invoke(instance, "   "));
            assertFalse((Boolean) verifyTwoFactorCode.invoke(instance, wrongCode),
                    "Hardcoded or random OTP must be rejected");
            assertTrue((Boolean) verifyTwoFactorCode.invoke(instance, issuedOtp),
                    "Issued OTP must succeed once");
            assertFalse((Boolean) verifyTwoFactorCode.invoke(instance, issuedOtp),
                    "Used OTP must be rejected on replay");
        } finally {
            if (instance instanceof JFrame frame) {
                frame.dispose();
            }
        }
    }

    @Test
    void testUserLoginVerifyCliModeAndServiceContracts() throws Exception {
        Class<?> loginClass = Class.forName("UserLogin");
        Method mainMethod = loginClass.getMethod("main", String[].class);
        assertDoesNotThrow(() -> mainMethod.invoke(null, (Object) new String[]{"--verify"}));

        assertTrue(WalletOperations.class.isAssignableFrom(WalletService.class),
                "WalletService must implement WalletOperations");
        assertTrue(AuthService.class.isInterface(), "AuthService must be an interface");
        assertTrue(LoanService.class.isInterface(), "LoanService must be an interface");
        assertTrue(InvestmentService.class.isInterface(), "InvestmentService must be an interface");
    }
}
