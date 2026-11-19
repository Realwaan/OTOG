package com.otog.model;

import com.otog.exception.InvalidAmountException;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Leaf node applying a percentage-based fee using {@link BigDecimal} arithmetic.
 */
public record PercentageFee(String name, BigDecimal rate) implements FeeComponent {

    public PercentageFee {
        name = (name != null && !name.isBlank()) ? name.trim() : "Percentage Fee";
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidAmountException("Fee percentage rate must be non-negative. Given: " + rate);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BigDecimal calculate(BigDecimal principalAmount) {
        BigDecimal validPrincipal = principalAmount != null
                ? MoneyUtil.validateNonNegativeAmount(principalAmount)
                : MoneyUtil.ZERO;
        if (rate.compareTo(BigDecimal.ZERO) == 0 || validPrincipal.compareTo(BigDecimal.ZERO) == 0) {
            return MoneyUtil.ZERO;
        }
        return validPrincipal.multiply(rate)
                .setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }
}
