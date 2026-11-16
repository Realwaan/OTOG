package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic mock implementation of BankAdapter for testing cash-in/out scenarios,
 * including successes, rejections, timeouts, and asynchronous status queries.
 */
public class MockBankAdapter implements BankAdapter {
    private volatile BankResponseStatus mode;
    private volatile BankResponseStatus defaultLookupStatus;
    private volatile String customFailureMessage;
    private final Map<String, BankResponseStatus> referenceStatusOverrides = new ConcurrentHashMap<>();
    private final Map<String, String> recordedProviderReferences = new ConcurrentHashMap<>();
    private final List<String> recordedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger callCount = new AtomicInteger(0);

    public MockBankAdapter() {
        this(BankResponseStatus.SUCCESS);
    }

    public MockBankAdapter(BankResponseStatus mode) {
        this.mode = mode != null ? mode : BankResponseStatus.SUCCESS;
    }

    public BankResponseStatus getMode() {
        return mode;
    }

    public void setMode(BankResponseStatus mode) {
        this.mode = mode != null ? mode : BankResponseStatus.SUCCESS;
    }

    public BankResponseStatus getDefaultLookupStatus() {
        return defaultLookupStatus;
    }

    public void setDefaultLookupStatus(BankResponseStatus defaultLookupStatus) {
        this.defaultLookupStatus = defaultLookupStatus;
    }

    public void setCustomFailureMessage(String customFailureMessage) {
        this.customFailureMessage = customFailureMessage;
    }

    public void setReferenceLookupStatus(String referenceNumber, BankResponseStatus status) {
        if (referenceNumber != null && status != null) {
            referenceStatusOverrides.put(referenceNumber, status);
        }
    }

    public List<String> getRecordedRequests() {
        return Collections.unmodifiableList(new ArrayList<>(recordedRequests));
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void reset() {
        recordedRequests.clear();
        referenceStatusOverrides.clear();
        recordedProviderReferences.clear();
        callCount.set(0);
        this.mode = BankResponseStatus.SUCCESS;
        this.defaultLookupStatus = null;
        this.customFailureMessage = null;
    }

    private String getOrCreateProviderReference(String referenceNumber, PaymentMethod paymentMethod) {
        return recordedProviderReferences.computeIfAbsent(referenceNumber, ref -> {
            String prefix = "DEMO-BANK";
            if (paymentMethod != null && paymentMethod.getProviderName() != null) {
                prefix = paymentMethod.getProviderName().replaceAll("[^a-zA-Z0-9]", "-").toUpperCase();
            }
            return "BNK-" + prefix + "-" + ref;
        });
    }

    @Override
    public BankApiResponse processCashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        callCount.incrementAndGet();
        recordedRequests.add("CASH_IN:" + referenceNumber + ":" + amount);
        String providerRef = getOrCreateProviderReference(referenceNumber, paymentMethod);

        return switch (mode) {
            case SUCCESS -> BankApiResponse.success(providerRef, "Cash-in deposit authorized by bank");
            case REJECTED -> BankApiResponse.rejected(providerRef,
                    customFailureMessage != null ? customFailureMessage : "Bank account debit declined: insufficient bank funds");
            case FAILED -> BankApiResponse.failed(providerRef,
                    customFailureMessage != null ? customFailureMessage : "Bank processing system error");
            case TIMEOUT -> BankApiResponse.timeout(providerRef, "Bank gateway connection timeout during cash-in");
            case PENDING -> BankApiResponse.pending(providerRef, "Cash-in deposit pending bank verification");
            case UNKNOWN -> BankApiResponse.unknown(providerRef, "Transaction outcome unknown; awaiting bank reconciliation");
        };
    }

    @Override
    public BankApiResponse processCashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        callCount.incrementAndGet();
        recordedRequests.add("CASH_OUT:" + referenceNumber + ":" + amount);
        String providerRef = getOrCreateProviderReference(referenceNumber, paymentMethod);

        return switch (mode) {
            case SUCCESS -> BankApiResponse.success(providerRef, "Cash-out withdrawal accepted and processed by bank");
            case REJECTED -> BankApiResponse.rejected(providerRef,
                    customFailureMessage != null ? customFailureMessage : "Beneficiary bank account invalid or rejected transfer");
            case FAILED -> BankApiResponse.failed(providerRef,
                    customFailureMessage != null ? customFailureMessage : "Interbank clearing network failure");
            case TIMEOUT -> BankApiResponse.timeout(providerRef, "Bank gateway timed out waiting for cash-out acknowledgment");
            case PENDING -> BankApiResponse.pending(providerRef, "Cash-out transfer queued for clearing settlement");
            case UNKNOWN -> BankApiResponse.unknown(providerRef, "Transaction outcome unknown; awaiting bank reconciliation");
        };
    }

    @Override
    public BankApiResponse queryStatus(String referenceNumber, String providerReference) {
        callCount.incrementAndGet();
        recordedRequests.add("QUERY_STATUS:" + referenceNumber + ":" + providerReference);
        String effectiveProviderRef = providerReference != null ? providerReference
                : recordedProviderReferences.getOrDefault(referenceNumber, "BNK-DEMO-" + referenceNumber);

        BankResponseStatus status = referenceStatusOverrides.get(referenceNumber);
        if (status == null) {
            status = defaultLookupStatus != null ? defaultLookupStatus : mode;
        }

        return switch (status) {
            case SUCCESS -> BankApiResponse.success(effectiveProviderRef, "Bank confirmed settlement complete");
            case REJECTED -> BankApiResponse.rejected(effectiveProviderRef,
                    customFailureMessage != null ? customFailureMessage : "Bank confirmed transaction rejected/canceled");
            case FAILED -> BankApiResponse.failed(effectiveProviderRef,
                    customFailureMessage != null ? customFailureMessage : "Bank confirmed transaction failed");
            case TIMEOUT -> BankApiResponse.timeout(effectiveProviderRef, "Bank gateway timed out during status query");
            case PENDING -> BankApiResponse.pending(effectiveProviderRef, "Transaction still pending bank settlement");
            case UNKNOWN -> BankApiResponse.unknown(effectiveProviderRef, "Transaction outcome unknown; awaiting bank reconciliation");
        };
    }
}
