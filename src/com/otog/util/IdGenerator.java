package com.otog.util;

import java.util.UUID;

/**
 * Utility for generating standardized, unique domain identifiers and references.
 */
public final class IdGenerator {
    private IdGenerator() {
    }

    public static String nextUserId() {
        return "USR-" + shortUuid();
    }

    public static String nextWalletId() {
        return "WLT-" + shortUuid();
    }

    public static String nextTransactionId() {
        return "TXN-" + shortUuid();
    }

    public static String nextPaymentMethodId() {
        return "PM-" + shortUuid();
    }

    public static String nextLedgerEntryId() {
        return "LED-" + shortUuid();
    }

    public static String nextReferenceNumber() {
        return "REQ-" + System.currentTimeMillis() + "-" + shortUuid().substring(0, 6);
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
