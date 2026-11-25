package com.otog.util;

import com.intellij.uiDesigner.core.Spacer;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Shared Swing EDT, exception unwrapping, responsive layout, and testable dialog callback
 * helpers reused across modular dashboard panels and top-level shell frames.
 */
public final class SwingUiSupport {

    private static final int DEFAULT_SCROLL_UNIT_INCREMENT = 16;

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

    /**
     * Upgrades a toolbar or button-row panel to {@link WrapLayout}, stripping any IntelliJ
     * {@link Spacer} children so buttons wrap smoothly onto additional rows without clipping.
     */
    public static void applyWrapLayout(JPanel panel, int align, int hgap, int vgap) {
        if (panel == null) {
            return;
        }
        List<Component> toRemove = new ArrayList<>();
        for (Component child : panel.getComponents()) {
            if (child instanceof Spacer) {
                toRemove.add(child);
            }
        }
        for (Component spacer : toRemove) {
            panel.remove(spacer);
        }
        panel.setLayout(new WrapLayout(align, hgap, vgap));
        panel.revalidate();
    }

    /**
     * Allows labels, text fields, combo boxes, or scroll containers to shrink horizontally
     * without forcing parent {@code GridLayoutManager} panels wider than the window viewport.
     */
    public static void makeShrinkable(JComponent... components) {
        if (components == null) {
            return;
        }
        for (JComponent comp : components) {
            if (comp != null) {
                Dimension currentMin = comp.getMinimumSize();
                int minHeight = currentMin != null ? Math.min(currentMin.height, 24) : 0;
                comp.setMinimumSize(new Dimension(0, Math.max(0, minHeight)));
            }
        }
    }

    /**
     * Wraps a panel in a borderless vertical {@link JScrollPane} whose viewport tracks
     * container width at all times (preventing horizontal overflow) and adds a vertical
     * scrollbar only when window height is smaller than the panel's preferred height.
     */
    public static JScrollPane createResponsiveScrollPane(JComponent content) {
        ViewportTrackingPanel wrapper = new ViewportTrackingPanel(content);
        JScrollPane scrollPane = new JScrollPane(
                wrapper,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(DEFAULT_SCROLL_UNIT_INCREMENT);
        scrollPane.setMinimumSize(new Dimension(0, 0));
        return scrollPane;
    }

    /**
     * Applies flexible window bounds, optionally packing to ensure preferred content never
     * clips (for compact dialogs) while clamping cleanly within usable screen bounds.
     */
    public static void applyFlexibleWindowBounds(Window window,
                                                 Dimension configuredSize,
                                                 Dimension minimumSize) {
        applyFlexibleWindowBounds(window, configuredSize, minimumSize, true);
    }

    public static void applyFlexibleWindowBounds(Window window,
                                                 Dimension configuredSize,
                                                 Dimension minimumSize,
                                                 boolean expandToPackedPreferredSize) {
        if (window == null) {
            return;
        }
        Dimension packed = null;
        if (expandToPackedPreferredSize) {
            window.pack();
            packed = window.getPreferredSize();
        }
        int targetW = Math.max(
                configuredSize != null ? configuredSize.width : 0,
                packed != null ? packed.width : 0
        );
        int targetH = Math.max(
                configuredSize != null ? configuredSize.height : 0,
                packed != null ? packed.height : 0
        );

        int minW = minimumSize != null ? minimumSize.width : 0;
        int minH = minimumSize != null ? minimumSize.height : 0;

        if (!GraphicsEnvironment.isHeadless()) {
            Rectangle maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (maxBounds != null && maxBounds.width > 0 && maxBounds.height > 0) {
                int maxW = Math.max(320, maxBounds.width - 32);
                int maxH = Math.max(240, maxBounds.height - 32);
                targetW = Math.min(targetW, maxW);
                targetH = Math.min(targetH, maxH);
                minW = Math.min(minW, maxW);
                minH = Math.min(minH, maxH);
            }
        }

        if (minW > 0 && minH > 0) {
            window.setMinimumSize(new Dimension(minW, minH));
            targetW = Math.max(targetW, minW);
            targetH = Math.max(targetH, minH);
        }
        window.setSize(new Dimension(targetW, targetH));
    }

    private static final class ViewportTrackingPanel extends JPanel implements Scrollable {
        private ViewportTrackingPanel(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            if (content != null) {
                add(content, BorderLayout.CENTER);
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return DEFAULT_SCROLL_UNIT_INCREMENT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(DEFAULT_SCROLL_UNIT_INCREMENT, visibleRect.height - DEFAULT_SCROLL_UNIT_INCREMENT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            if (getParent() instanceof JViewport viewport) {
                return viewport.getHeight() >= getPreferredSize().height;
            }
            return false;
        }
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
