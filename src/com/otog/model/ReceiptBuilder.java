package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Creational Builder constructing structured {@link ReceiptComponent} trees and
 * formatted receipt/confirmation strings without duplicating string formatting logic.
 */
public class ReceiptBuilder {

    public static final int DEFAULT_LABEL_WIDTH = 14;
    private static final String THIN_DIVIDER = "----------------------------------------";

    private String bannerTitle;
    private int labelWidth = DEFAULT_LABEL_WIDTH;
    private final ReceiptSection bodySection = new ReceiptSection(null, false, true);

    public ReceiptBuilder banner(String bannerTitle) {
        this.bannerTitle = bannerTitle;
        return this;
    }

    public ReceiptBuilder labelWidth(int labelWidth) {
        this.labelWidth = Math.max(8, labelWidth);
        return this;
    }

    public ReceiptBuilder line(String label, Object value) {
        bodySection.add(new ReceiptLine(label, Objects.toString(value, "")));
        return this;
    }

    public ReceiptBuilder optionalLine(String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            bodySection.add(new ReceiptLine(label, value));
        }
        return this;
    }

    public ReceiptBuilder moneyLine(String label, BigDecimal amount, String currency) {
        bodySection.add(new ReceiptLine(label, MoneyUtil.format(amount, currency)));
        return this;
    }

    public ReceiptBuilder divider() {
        bodySection.add(new ReceiptLine(null, THIN_DIVIDER));
        return this;
    }

    public ReceiptBuilder section(ReceiptSection nestedSection) {
        if (nestedSection != null) {
            bodySection.add(nestedSection);
        }
        return this;
    }

    public ReceiptComponent buildComponent() {
        ReceiptSection root = new ReceiptSection(bannerTitle, false, false);
        root.add(bodySection);
        return root;
    }

    public String build() {
        return buildComponent().renderToString(labelWidth);
    }
}
