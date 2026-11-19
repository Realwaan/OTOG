package com.otog.service;

import com.otog.model.PaymentMethod;
import com.otog.util.MoneyUtil;

import java.math.BigDecimal;

/**
 * Structural Decorator enforcing non-blank reference numbers and positive 2-decimal
 * {@link BigDecimal} monetary invariants before invoking the wrapped {@link BankAdapter}.
 */
public class ValidatingBankAdapterDecorator extends BankAdapterDecorator {

    public ValidatingBankAdapterDecorator(BankAdapter delegate) {
        super(delegate);
    }

    @Override
    public BankApiResponse processCashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        String validRef = requireReferenceNumber(referenceNumber);
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        return super.processCashIn(validRef, paymentMethod, validAmount);
    }

    @Override
    public BankApiResponse processCashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        String validRef = requireReferenceNumber(referenceNumber);
        BigDecimal validAmount = MoneyUtil.validatePositiveAmount(amount);
        return super.processCashOut(validRef, paymentMethod, validAmount);
    }

    @Override
    public BankApiResponse queryStatus(String referenceNumber, String providerReference) {
        String validRef = requireReferenceNumber(referenceNumber);
        return super.queryStatus(validRef, providerReference);
    }

    private static String requireReferenceNumber(String referenceNumber) {
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Reference number must not be null or empty");
        }
        return referenceNumber.trim();
    }
}
