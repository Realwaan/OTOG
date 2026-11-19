package com.otog.model;

import java.math.BigDecimal;

/**
 * Structural Composite component for composable {@link BigDecimal} transaction fee rules.
 */
public sealed interface FeeComponent permits FlatFee, PercentageFee, CompositeFeePolicy {

    String getName();

    BigDecimal calculate(BigDecimal principalAmount);
}
