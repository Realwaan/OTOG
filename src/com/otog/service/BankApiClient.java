package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Bank API Client handling communication with banking rails and payment processors.
 * Employs the {@link BankAdapter} Adapter interface and unwraps {@link BankAdapterDecorator}
 * chains transparently for deterministic mock configuration.
 */
public class BankApiClient {
    private BankAdapter adapter;

    public BankApiClient() {
        this(new AuditingBankAdapterDecorator(
                new ValidatingBankAdapterDecorator(new MockBankAdapter(BankResponseStatus.SUCCESS))));
    }

    public BankApiClient(BankAdapter adapter) {
        this.adapter = adapter != null
                ? adapter
                : new AuditingBankAdapterDecorator(
                new ValidatingBankAdapterDecorator(new MockBankAdapter(BankResponseStatus.SUCCESS)));
    }

    public BankApiClient(BankResponseStatus mode) {
        this(new AuditingBankAdapterDecorator(
                new ValidatingBankAdapterDecorator(new MockBankAdapter(mode))));
    }

    public BankAdapter getAdapter() {
        return adapter;
    }

    public <T extends BankAdapter> Optional<T> findAdapter(Class<T> adapterType) {
        return BankAdapterDecorator.findAdapter(adapter, adapterType);
    }

    public void setAdapter(BankAdapter adapter) {
        this.adapter = adapter != null
                ? adapter
                : new AuditingBankAdapterDecorator(
                new ValidatingBankAdapterDecorator(new MockBankAdapter(BankResponseStatus.SUCCESS)));
    }

    public void setMode(BankResponseStatus mode) {
        findAdapter(MockBankAdapter.class).ifPresent(mock -> mock.setMode(mode));
    }

    public void setDefaultLookupStatus(BankResponseStatus status) {
        findAdapter(MockBankAdapter.class).ifPresent(mock -> mock.setDefaultLookupStatus(status));
    }

    public void setReferenceLookupStatus(String referenceNumber, BankResponseStatus status) {
        findAdapter(MockBankAdapter.class).ifPresent(mock -> mock.setReferenceLookupStatus(referenceNumber, status));
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
