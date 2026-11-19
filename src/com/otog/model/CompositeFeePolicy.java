package com.otog.model;

import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Composite node aggregating multiple {@link FeeComponent} rules (flat, percentage, or nested
 * policies) into a single summed {@link BigDecimal} fee.
 */
public non-sealed class CompositeFeePolicy implements FeeComponent {

    private final String name;
    private final List<FeeComponent> components = new ArrayList<>();

    public CompositeFeePolicy(String name) {
        this.name = (name != null && !name.isBlank()) ? name.trim() : "Composite Fee Policy";
    }

    public static CompositeFeePolicy fromConfig() {
        OtogConfig config = OtogConfig.getInstance();
        CompositeFeePolicy policy = new CompositeFeePolicy("Configured Send Money Fee Policy");
        BigDecimal flat = config.getSendDefaultFlatFee();
        BigDecimal percent = config.getSendDefaultPercentFee();
        if (flat.compareTo(BigDecimal.ZERO) > 0) {
            policy.add(new FlatFee("Configured Flat Fee", flat));
        }
        if (percent.compareTo(BigDecimal.ZERO) > 0) {
            policy.add(new PercentageFee("Configured Percentage Fee", percent));
        }
        return policy;
    }

    public CompositeFeePolicy add(FeeComponent component) {
        if (component != null) {
            components.add(component);
        }
        return this;
    }

    public List<FeeComponent> getComponents() {
        return Collections.unmodifiableList(components);
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
        BigDecimal total = MoneyUtil.ZERO;
        for (FeeComponent component : components) {
            total = total.add(component.calculate(validPrincipal));
        }
        return total.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING);
    }
}
