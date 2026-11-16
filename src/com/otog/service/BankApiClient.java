package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;

/**
 * Bank API Client handling communication with banking rails and payment processors.
 * Employs an adapter interface to support both mock simulation and real-world endpoints.
 */
public class BankApiClient {
    private BankAdapter adapter;

    public BankApiClient() {
        this(new MockBankAdapter(BankResponseStatus.SUCCESS));
    }

    public BankApiClient(BankAdapter adapter) {
        this.adapter = adapter != null ? adapter : new MockBankAdapter(BankResponseStatus.SUCCESS);
    }

    public BankApiClient(BankResponseStatus mode) {
        this(new MockBankAdapter(mode));
    }

    public BankAdapter getAdapter() {
        return adapter;
    }

    public void setAdapter(BankAdapter adapter) {
        this.adapter = adapter != null ? adapter : new MockBankAdapter(BankResponseStatus.SUCCESS);
    }

    public void setMode(BankResponseStatus mode) {
        if (adapter instanceof MockBankAdapter mock) {
            mock.setMode(mode);
        }
    }

    public void setDefaultLookupStatus(BankResponseStatus status) {
        if (adapter instanceof MockBankAdapter mock) {
            mock.setDefaultLookupStatus(status);
        }
    }

    public void setReferenceLookupStatus(String referenceNumber, BankResponseStatus status) {
        if (adapter instanceof MockBankAdapter mock) {
            mock.setReferenceLookupStatus(referenceNumber, status);
        }
    }

    public BankApiResponse cashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        return adapter.processCashIn(referenceNumber, paymentMethod, amount);
    }

    public BankApiResponse cashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        return adapter.processCashOut(referenceNumber, paymentMethod, amount);
    }

    public BankApiResponse checkStatus(String referenceNumber, String providerReference) {
        return adapter.queryStatus(referenceNumber, providerReference);
    }

    public BankApiResponse queryStatus(String referenceNumber, String providerReference) {
        return adapter.queryStatus(referenceNumber, providerReference);
    }
}
