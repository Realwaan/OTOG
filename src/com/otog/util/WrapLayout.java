package com.otog.util;

import com.intellij.uiDesigner.core.AbstractLayout;

import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Responsive {@link FlowLayout} subclass that wraps components cleanly across multiple rows
 * whenever container width shrinks, and reports a minimum width based on the widest single
 * child so parent {@code GridLayoutManager} shells never clip buttons or overflow horizontally.
 */
public class WrapLayout extends FlowLayout {

    private int lastEvaluatedWidth = -1;
    private int lastEvaluatedHeight = -1;

    public WrapLayout() {
        super();
    }

    public WrapLayout(int align) {
        super(align);
    }

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width = computeMinWidth(target);
        return minimum;
    }

    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        int currentWidth = target.getWidth();
        if (currentWidth <= 0) {
            return;
        }
        Dimension required = layoutSize(target, true);
        if (currentWidth != lastEvaluatedWidth || required.height != lastEvaluatedHeight) {
            lastEvaluatedWidth = currentWidth;
            lastEvaluatedHeight = required.height;
            if (Math.abs(target.getHeight() - required.height) > 1) {
                Container parent = target.getParent();
                if (parent != null) {
                    SwingUtilities.invokeLater(() -> {
                        target.revalidate();
                        parent.revalidate();
                        parent.repaint();
                    });
                }
            }
        }
    }

    private int computeMinWidth(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int hgap = getHgap();
            int widest = 0;
            int count = target.getComponentCount();
            for (int i = 0; i < count; i++) {
                Component m = target.getComponent(i);
                if (m.isVisible()) {
                    Dimension d = m.getPreferredSize();
                    widest = Math.max(widest, d.width);
                }
            }
            if (widest == 0) {
                return 0;
            }
            return widest + insets.left + insets.right + (hgap * 2);
        }
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = resolveAvailableWidth(target);
            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth > horizontalInsetsAndGap
                    ? targetWidth - horizontalInsetsAndGap
                    : Integer.MAX_VALUE;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            int nmembers = target.getComponentCount();

            for (int i = 0; i < nmembers; i++) {
                Component m = target.getComponent(i);
                if (m.isVisible()) {
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                        addRow(dim, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    if (rowWidth != 0) {
                        rowWidth += hgap;
                    }
                    rowWidth += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                }
            }

            addRow(dim, rowWidth, rowHeight);

            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + (vgap * 2);

            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width = Math.max(0, dim.width - (hgap + 1));
            }
            return dim;
        }
    }

    private static int resolveAvailableWidth(Container target) {
        int ownWidth = target.getWidth();
        int constraintWidth = Integer.MAX_VALUE;

        Container ancestor = target.getParent();
        int accumulatedInsetPadding = 0;
        while (ancestor != null) {
            Insets aInsets = ancestor.getInsets();
            int layoutMargin = 0;
            LayoutManager lm = ancestor.getLayout();
            if (lm instanceof AbstractLayout abstractLayout) {
                Insets m = abstractLayout.getMargin();
                if (m != null) {
                    layoutMargin = m.left + m.right;
                }
            }
            int aw = ancestor.getWidth();
            if (aw > 0) {
                int inner = aw - aInsets.left - aInsets.right - layoutMargin - accumulatedInsetPadding;
                if (inner > 0) {
                    constraintWidth = Math.min(constraintWidth, inner);
                }
                if (ancestor instanceof JViewport) {
                    break;
                }
            }
            accumulatedInsetPadding += aInsets.left + aInsets.right + layoutMargin;
            ancestor = ancestor.getParent();
        }

        if (ownWidth > 0) {
            // During downward window shrinkage, parent's inner width updates before target.setBounds().
            return constraintWidth < Integer.MAX_VALUE ? Math.min(ownWidth, constraintWidth) : ownWidth;
        }
        return constraintWidth < Integer.MAX_VALUE ? constraintWidth : Integer.MAX_VALUE;
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}
