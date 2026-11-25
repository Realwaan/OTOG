package com.otog.util;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Shared Swing EDT, exception unwrapping, and testable dialog callback helpers
 * reused across modular dashboard panels and top-level shell frames.
 */
public final class SwingUiSupport {

    @FunctionalInterface
    public interface DialogMessageCallback {
        void show(Component parent, String title, String message, int messageType);
    }

    @FunctionalInterface
    public interface ComponentMessageCallback {
        void show(Component parent, String title, Object message, int messageType);
    }

    @FunctionalInterface
    public interface ConfirmDialogCallback {
        int show(Component parent, String title, Object message, int optionType, int messageType);
    }

    private SwingUiSupport() {
    }

    public static DialogMessageCallback defaultMessageCallback() {
        return (parent, title, msg, type) -> JOptionPane.showMessageDialog(parent, msg, title, type);
    }

    public static ComponentMessageCallback defaultComponentMessageCallback() {
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
}
