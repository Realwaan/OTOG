package com.otog.util;

import com.otog.exception.InvalidAmountException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Utility for monetary validations, explicit PHP currency precision, and formatting.
 */
public final class MoneyUtil {
    public static final String DEFAULT_CURRENCY = "PHP";
    public static final int DEFAULT_SCALE = 2;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);

    private MoneyUtil() {
        // utility class
    }

    /**
     * Normalizes and validates that a monetary amount is strictly positive (> 0)
     * and adheres to 2 decimal places precision.
     *
     * @param amount the amount to validate
     * @return the normalized BigDecimal scaled to 2 decimal places
     * @throws InvalidAmountException if amount is null, <= 0, or exceeds 2 decimal places
     */
    public static BigDecimal validatePositiveAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Monetary amount cannot be null");
        }
        validateScale(amount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Monetary amount must be strictly positive (> 0). Given: " + amount);
        }
        return amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    /**
     * Validates that an amount (such as a fee) is non-negative (>= 0)
     * and adheres to 2 decimal places precision.
     *
     * @param amount the amount to validate
     * @return the normalized BigDecimal scaled to 2 decimal places
     * @throws InvalidAmountException if amount is null, < 0, or exceeds 2 decimal places
     */
    public static BigDecimal validateNonNegativeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Monetary amount cannot be null");
        }
        validateScale(amount);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidAmountException("Monetary amount cannot be negative (< 0). Given: " + amount);
        }
        return amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    /**
     * Validates currency code is present and valid.
     *
     * @param currency currency code (e.g. PHP)
     * @return uppercase trimmed currency code
     */
    public static String validateCurrency(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return DEFAULT_CURRENCY;
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3) {
            throw new InvalidAmountException("Currency code must be a 3-letter ISO-4217 code. Given: " + currency);
        }
        return normalized;
    }

    /**
     * Ensures the scale does not exceed 2 decimal places (ignoring trailing zeros).
     */
    private static void validateScale(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > DEFAULT_SCALE) {
            throw new InvalidAmountException(String.format(
                    "Monetary amount '%s' exceeds standard currency precision of %d decimal places",
                    amount, DEFAULT_SCALE));
        }
    }

    /**
     * Formats an amount with currency symbol/code (e.g. "PHP 1,234.50").
     */
    public static String format(BigDecimal amount, String currency) {
        if (amount == null) {
            return (currency != null ? currency : DEFAULT_CURRENCY) + " 0.00";
        }
        BigDecimal scaled = amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        DecimalFormat df = new DecimalFormat("#,##0.00", symbols);
        String curr = (currency != null && !currency.trim().isEmpty()) ? currency.trim().toUpperCase(Locale.ROOT) : DEFAULT_CURRENCY;
        return curr + " " + df.format(scaled);
    }

    /**
     * Parses a string into a validated 2-decimal BigDecimal.
     */
    public static BigDecimal parse(String str) {
        if (str == null || str.trim().isEmpty()) {
            throw new InvalidAmountException("Amount string cannot be empty");
        }
        try {
            BigDecimal bd = new BigDecimal(str.trim());
            return bd.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException("Invalid number format for monetary amount: " + str, e);
        }
    }
}
