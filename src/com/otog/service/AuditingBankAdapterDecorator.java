package com.otog.service;

import com.otog.model.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Structural Decorator recording structured, bounded, secret-free audit events
 * for external banking gateway calls without altering the wrapped {@link BankAdapter}.
 */
public class AuditingBankAdapterDecorator extends BankAdapterDecorator {

    public static final int DEFAULT_MAX_AUDIT_ENTRIES = 200;

    /**
     * Immutable audit entry capturing a single gateway call outcome without credentials or PII.
     */
    public record BankCallAudit(String operation,
                                String referenceNumber,
                                String providerName,
                                BigDecimal amount,
                                BankResponseStatus status,
                                String providerReference,
                                Instant timestamp) {
    }

    private final int maxEntries;
    private final Deque<BankCallAudit> auditTrail = new ArrayDeque<>();
    private final Object auditLock = new Object();

    public AuditingBankAdapterDecorator(BankAdapter delegate) {
        this(delegate, DEFAULT_MAX_AUDIT_ENTRIES);
    }

    public AuditingBankAdapterDecorator(BankAdapter delegate, int maxEntries) {
        super(delegate);
        this.maxEntries = Math.max(10, maxEntries);
    }

    @Override
    public BankApiResponse processCashIn(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        BankApiResponse response = super.processCashIn(referenceNumber, paymentMethod, amount);
        record("CASH_IN", referenceNumber, resolveProvider(paymentMethod), amount, response);
        return response;
    }

    @Override
    public BankApiResponse processCashOut(String referenceNumber, PaymentMethod paymentMethod, BigDecimal amount) {
        BankApiResponse response = super.processCashOut(referenceNumber, paymentMethod, amount);
        record("CASH_OUT", referenceNumber, resolveProvider(paymentMethod), amount, response);
        return response;
    }

    @Override
    public BankApiResponse queryStatus(String referenceNumber, String providerReference) {
        BankApiResponse response = super.queryStatus(referenceNumber, providerReference);
        record("STATUS_QUERY", referenceNumber, "BANK_GATEWAY", null, response);
        return response;
    }

    public List<BankCallAudit> getAuditTrail() {
        synchronized (auditLock) {
            return Collections.unmodifiableList(new ArrayList<>(auditTrail));
        }
    }

    public void clearAuditTrail() {
        synchronized (auditLock) {
            auditTrail.clear();
        }
    }

    private void record(String operation, String referenceNumber, String providerName,
                        BigDecimal amount, BankApiResponse response) {
        BankResponseStatus status = response != null ? response.getStatus() : BankResponseStatus.UNKNOWN;
        String providerRef = response != null ? response.getProviderReference() : null;
        BankCallAudit entry = new BankCallAudit(
                operation,
                referenceNumber,
                providerName,
                amount,
                status,
                providerRef,
                Instant.now()
        );
        synchronized (auditLock) {
            while (auditTrail.size() >= maxEntries) {
                auditTrail.removeFirst();
            }
            auditTrail.addLast(entry);
        }
    }

    private static String resolveProvider(PaymentMethod pm) {
        return (pm != null && pm.getProviderName() != null && !pm.getProviderName().isBlank())
                ? pm.getProviderName()
                : "DEFAULT_CHANNEL";
    }
}
