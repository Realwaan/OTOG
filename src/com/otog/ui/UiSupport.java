package com.otog.ui;

import com.otog.exception.WalletException;
import com.otog.service.AuthService;
import com.otog.service.SessionManager;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Shared Swing EDT, exception unwrapping, dialog callback, and Transaction PIN
 * verification helpers reused across dashboard panels and top-level shell frames.
 */
public final class UiSupport {

    @FunctionalInterface
    public interface DialogMessageCallback {
        void show(Component parent, String title, String message, int messageType);
    }

    @FunctionalInterface
    public interface ConfirmDialogCallback {
        int show(Component parent, String title, Object message, int optionType, int messageType);
    }

    private UiSupport() {
    }

    public static DialogMessageCallback defaultMessageCallback() {
        return (parent, title, msg, type) -> JOptionPane.showMessageDialog(parent, msg, title, type);
    }

    public static ConfirmDialogCallback defaultConfirmCallback() {
        return (parent, title, msg, opt, type) -> JOptionPane.showConfirmDialog(parent, msg, title, opt, type);
    }

    public static void runOnEdt(Runnable action) {
        if (action == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    public static Throwable unwrapCause(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static String extractErrorMessage(Throwable ex) {
        if (ex == null) {
            return "An unexpected error occurred.";
        }
        String msg = ex.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : ex.getClass().getSimpleName();
    }

    public static void verifyPinOrThrow(AuthService authService, String userId, String rawPin) {
        try {
            if (!authService.verifyPin(userId, rawPin)) {
                SessionManager session = SessionManager.getInstance();
                if (session.isLockedOutByPin()) {
                    throw new WalletException("Session terminated after maximum failed PIN attempts.");
                }
                throw new WalletException("Invalid transaction PIN. Outgoing payment blocked.");
            }
        } catch (SecurityException ex) {
            throw new WalletException(ex.getMessage(), ex);
        }
    }
}
