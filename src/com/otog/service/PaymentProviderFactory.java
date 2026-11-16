package com.otog.service;

/**
 * Factory / Abstract Factory for creating payment provider adapters and BankApiClient instances
 * configured for deterministic operational modes (SUCCESS, FAILURE/REJECTED, TIMEOUT, PENDING).
 */
public interface PaymentProviderFactory {

    BankAdapter createBankAdapter();

    BankApiClient createBankApiClient();

    // Default alias methods
    default BankAdapter createAdapter() {
        return createBankAdapter();
    }

    default BankApiClient createClient() {
        return createBankApiClient();
    }

    // --- Static Abstract Factory Builders ---

    static PaymentProviderFactory forMode(BankResponseStatus status) {
        return new DeterministicPaymentProviderFactory(status);
    }

    static PaymentProviderFactory forMode(BankResponseStatus initialStatus, BankResponseStatus lookupStatus) {
        return new DeterministicPaymentProviderFactory(initialStatus, lookupStatus);
    }

    static PaymentProviderFactory successFactory() {
        return new SuccessPaymentProviderFactory();
    }

    static PaymentProviderFactory failureFactory() {
        return new FailurePaymentProviderFactory();
    }

    static PaymentProviderFactory timeoutFactory() {
        return new TimeoutPaymentProviderFactory();
    }

    static PaymentProviderFactory pendingFactory() {
        return new PendingPaymentProviderFactory();
    }

    static PaymentProviderFactory unknownFactory() {
        return new UnknownPaymentProviderFactory();
    }

    // --- Direct Factory Methods for BankApiClient ---

    static BankApiClient createSuccessClient() {
        return successFactory().createBankApiClient();
    }

    static BankApiClient createFailureClient() {
        return failureFactory().createBankApiClient();
    }

    static BankApiClient createTimeoutClient() {
        return timeoutFactory().createBankApiClient();
    }

    static BankApiClient createPendingClient() {
        return pendingFactory().createBankApiClient();
    }

    static BankApiClient createUnknownClient() {
        return unknownFactory().createBankApiClient();
    }

    static BankApiClient createClient(BankResponseStatus status) {
        return forMode(status).createBankApiClient();
    }

    static BankApiClient createDefaultClient() {
        return createSuccessClient();
    }

    // --- Direct Factory Methods for BankAdapter ---

    static BankAdapter createSuccessAdapter() {
        return successFactory().createBankAdapter();
    }

    static BankAdapter createFailureAdapter() {
        return failureFactory().createBankAdapter();
    }

    static BankAdapter createTimeoutAdapter() {
        return timeoutFactory().createBankAdapter();
    }

    static BankAdapter createPendingAdapter() {
        return pendingFactory().createBankAdapter();
    }

    static BankAdapter createUnknownAdapter() {
        return unknownFactory().createBankAdapter();
    }

    static BankAdapter createAdapter(BankResponseStatus status) {
        return forMode(status).createBankAdapter();
    }

    // --- Concrete Implementation Classes (Abstract Factory Variants) ---

    class DeterministicPaymentProviderFactory implements PaymentProviderFactory {
        private final BankResponseStatus initialMode;
        private final BankResponseStatus lookupMode;

        public DeterministicPaymentProviderFactory(BankResponseStatus initialMode) {
            this(initialMode, null);
        }

        public DeterministicPaymentProviderFactory(BankResponseStatus initialMode, BankResponseStatus lookupMode) {
            this.initialMode = initialMode != null ? initialMode : BankResponseStatus.SUCCESS;
            this.lookupMode = lookupMode;
        }

        @Override
        public BankAdapter createBankAdapter() {
            MockBankAdapter adapter = new MockBankAdapter(initialMode);
            if (lookupMode != null) {
                adapter.setDefaultLookupStatus(lookupMode);
            }
            return adapter;
        }

        @Override
        public BankApiClient createBankApiClient() {
            return new BankApiClient(createBankAdapter());
        }
    }

    class SuccessPaymentProviderFactory extends DeterministicPaymentProviderFactory {
        public SuccessPaymentProviderFactory() {
            super(BankResponseStatus.SUCCESS, BankResponseStatus.SUCCESS);
        }
    }

    class FailurePaymentProviderFactory extends DeterministicPaymentProviderFactory {
        public FailurePaymentProviderFactory() {
            super(BankResponseStatus.REJECTED, BankResponseStatus.REJECTED);
        }
    }

    class TimeoutPaymentProviderFactory extends DeterministicPaymentProviderFactory {
        public TimeoutPaymentProviderFactory() {
            super(BankResponseStatus.TIMEOUT, BankResponseStatus.TIMEOUT);
        }

        public TimeoutPaymentProviderFactory(BankResponseStatus lookupMode) {
            super(BankResponseStatus.TIMEOUT, lookupMode);
        }
    }

    class PendingPaymentProviderFactory extends DeterministicPaymentProviderFactory {
        public PendingPaymentProviderFactory() {
            super(BankResponseStatus.PENDING, BankResponseStatus.PENDING);
        }

        public PendingPaymentProviderFactory(BankResponseStatus lookupMode) {
            super(BankResponseStatus.PENDING, lookupMode);
        }
    }

    class UnknownPaymentProviderFactory extends DeterministicPaymentProviderFactory {
        public UnknownPaymentProviderFactory() {
            super(BankResponseStatus.UNKNOWN, BankResponseStatus.UNKNOWN);
        }

        public UnknownPaymentProviderFactory(BankResponseStatus lookupMode) {
            super(BankResponseStatus.UNKNOWN, lookupMode);
        }
    }
}
