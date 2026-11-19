package com.otog.model;

import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Leaf node applying a fixed monetary fee.
 */
public record FlatFee(String name, BigDecimal flatAmount) implements FeeComponent {

    public FlatFee {
        name = (name != null && !name.isBlank()) ? name.trim() : "Flat Fee";
        flatAmount = flatAmount != null ? MoneyUtil.validateNonNegativeAmount(flatAmount) : MoneyUtil.ZERO;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BigDecimal calculate(BigDecimal principalAmount) {
        if (principalAmount != null) {
            MoneyUtil.validateNonNegativeAmount(principalAmount);
        }
        return flatAmount;
    }
}
