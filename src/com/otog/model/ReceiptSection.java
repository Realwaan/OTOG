package com.otog.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composite node in the {@link ReceiptComponent} hierarchy grouping child lines and
 * nested sections with optional top/bottom borders.
 */
public non-sealed class ReceiptSection implements ReceiptComponent {

    private static final String HEAVY_DIVIDER = "========================================";
    private static final String THIN_DIVIDER = "----------------------------------------";

    private final String title;
    private final List<ReceiptComponent> children = new ArrayList<>();
    private final boolean showTopDivider;
    private final boolean showBottomDivider;

    public ReceiptSection(String title, boolean showTopDivider, boolean showBottomDivider) {
        this.title = title;
        this.showTopDivider = showTopDivider;
        this.showBottomDivider = showBottomDivider;
    }

    public String getTitle() {
        return title;
    }

    public void add(ReceiptComponent component) {
        if (component != null) {
            children.add(component);
        }
    }

    public List<ReceiptComponent> getChildren() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void render(StringBuilder sb, int labelWidth) {
        if (sb == null) {
            return;
        }
        if (title != null && !title.isBlank()) {
            sb.append(HEAVY_DIVIDER).append('\n');
            sb.append(centerText(title.trim(), HEAVY_DIVIDER.length())).append('\n');
            sb.append(HEAVY_DIVIDER).append('\n');
        } else if (showTopDivider) {
            sb.append(THIN_DIVIDER).append('\n');
        }

        for (ReceiptComponent child : children) {
            child.render(sb, labelWidth);
        }

        if (showBottomDivider) {
            sb.append(title != null && !title.isBlank() ? HEAVY_DIVIDER : THIN_DIVIDER).append('\n');
        }
    }

    private static String centerText(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        int pad = (width - text.length()) / 2;
        return " ".repeat(pad) + text;
    }
}
