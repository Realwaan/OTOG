package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;

/**
 * Adapter interface decoupling the OTOG wallet system from external banking APIs.
 */
public interface BankAdapter {
    BankApiResponse processCashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount);

    BankApiResponse processCashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount);

    BankApiResponse queryStatus(String referenceNumber, String providerReference);
}
