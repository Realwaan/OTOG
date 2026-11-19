package com.otog.model;

/**
 * Structural Composite component representing a renderable element within a transaction receipt
 * or pre-confirmation summary.
 */
public sealed interface ReceiptComponent permits ReceiptLine, ReceiptSection {

    void render(StringBuilder sb, int labelWidth);

    default String renderToString(int labelWidth) {
        StringBuilder sb = new StringBuilder();
        render(sb, labelWidth);
        return sb.toString();
    }
}
