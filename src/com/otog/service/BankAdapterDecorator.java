package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Structural Decorator base wrapping a {@link BankAdapter} to layer cross-cutting concerns
 * (input validation, structured call auditing) transparently without altering provider adapters.
 */
public abstract class BankAdapterDecorator implements BankAdapter {

    protected final BankAdapter delegate;

    protected BankAdapterDecorator(BankAdapter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate BankAdapter cannot be null");
    }

    public BankAdapter getDelegate() {
        return delegate;
    }

    /**
     * Unwraps all decorator layers to return the innermost concrete {@link BankAdapter}.
     */
    public static BankAdapter unwrapRoot(BankAdapter adapter) {
        BankAdapter current = adapter;
        while (current instanceof BankAdapterDecorator decorator) {
            current = decorator.getDelegate();
        }
        return current;
    }

    /**
     * Searches through decorator layers for an adapter instance matching {@code adapterType}.
     */
    public static <T extends BankAdapter> Optional<T> findAdapter(BankAdapter adapter, Class<T> adapterType) {
        if (adapterType == null) {
            return Optional.empty();
        }
        BankAdapter current = adapter;
        while (current != null) {
            if (adapterType.isInstance(current)) {
                return Optional.of(adapterType.cast(current));
            }
            if (current instanceof BankAdapterDecorator decorator) {
                current = decorator.getDelegate();
            } else {
                break;
            }
        }
        return Optional.empty();
    }

    @Override
    public BankApiResponse processCashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        return delegate.processCashIn(referenceNumber, paymentMethod, amount);
    }

    @Override
    public BankApiResponse processCashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        return delegate.processCashOut(referenceNumber, paymentMethod, amount);
    }

    @Override
    public BankApiResponse queryStatus(String referenceNumber, String providerReference) {
        return delegate.queryStatus(referenceNumber, providerReference);
    }
}
