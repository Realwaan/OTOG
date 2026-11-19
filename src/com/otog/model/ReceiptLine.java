package com.otog.model;

/**
 * Leaf node in the {@link ReceiptComponent} Composite hierarchy representing a single
 * labeled key-value line or raw banner/divider line.
 */
public record ReceiptLine(String label, String value) implements ReceiptComponent {

    @Override
    public void render(StringBuilder sb, int labelWidth) {
        if (sb == null) {
            return;
        }
        String safeValue = value != null ? value : "";
        if (label == null || label.isBlank()) {
            sb.append(safeValue).append('\n');
            return;
        }
        String prefix = label.trim() + ":";
        int width = Math.max(prefix.length() + 1, labelWidth);
        sb.append(String.format("%-" + width + "s%s%n", prefix, safeValue));
    }
}
