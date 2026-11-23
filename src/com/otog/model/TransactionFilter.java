package com.otog.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;

/**
 * Immutable filter criteria and sorting comparators for owned transaction history queries
 * (date range, transaction type, transaction status, and optional keyword/reference match).
 */
public final class TransactionFilter {

    public static final Comparator<Transaction> NEWEST_FIRST = Comparator
            .comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Transaction::getTransactionId, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Transaction::getReferenceNumber, Comparator.nullsLast(Comparator.naturalOrder()));

    public static final Comparator<Transaction> OLDEST_FIRST = NEWEST_FIRST.reversed();

    public static final Comparator<Transaction> AMOUNT_DESC = Comparator
            .comparing(Transaction::getAmount, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(NEWEST_FIRST);

    public static final Comparator<Transaction> AMOUNT_ASC = Comparator
            .comparing(Transaction::getAmount, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NEWEST_FIRST);

    private static final TransactionFilter EMPTY = new Builder().build();

    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final TransactionType type;
    private final TransactionStatus status;
    private final String keyword;
    private final int limit;

    private TransactionFilter(Builder builder) {
        if (builder.fromDate != null && builder.toDate != null && builder.fromDate.isAfter(builder.toDate)) {
            throw new IllegalArgumentException("Start date (" + builder.fromDate
                    + ") cannot be after end date (" + builder.toDate + ")");
        }
        if (builder.limit < 0) {
            throw new IllegalArgumentException("Filter limit cannot be negative");
        }
        this.fromDate = builder.fromDate;
        this.toDate = builder.toDate;
        this.type = builder.type;
        this.status = builder.status;
        this.keyword = (builder.keyword != null && !builder.keyword.trim().isEmpty())
                ? builder.keyword.trim()
                : null;
        this.limit = builder.limit;
    }

    public static TransactionFilter empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getKeyword() {
        return keyword;
    }

    public int getLimit() {
        return limit;
    }

    public boolean isEmpty() {
        return fromDate == null && toDate == null && type == null && status == null && keyword == null;
    }

    /**
     * Evaluates whether the supplied transaction satisfies every active predicate in this filter.
     */
    public boolean matches(Transaction txn) {
        if (txn == null) {
            return false;
        }
        if (type != null && txn.getType() != type) {
            return false;
        }
        if (status != null && txn.getStatus() != status) {
            return false;
        }
        if (fromDate != null || toDate != null) {
            LocalDateTime createdAt = txn.getCreatedAt();
            if (createdAt == null) {
                return false;
            }
            LocalDate txDate = createdAt.toLocalDate();
            if (fromDate != null && txDate.isBefore(fromDate)) {
                return false;
            }
            if (toDate != null && txDate.isAfter(toDate)) {
                return false;
            }
        }
        if (keyword != null) {
            String lower = keyword.toLowerCase(Locale.ROOT);
            boolean refMatch = txn.getReferenceNumber() != null
                    && txn.getReferenceNumber().toLowerCase(Locale.ROOT).contains(lower);
            boolean provMatch = txn.getProviderReference() != null
                    && txn.getProviderReference().toLowerCase(Locale.ROOT).contains(lower);
            boolean descMatch = txn.getDescription() != null
                    && txn.getDescription().toLowerCase(Locale.ROOT).contains(lower);
            if (!refMatch && !provMatch && !descMatch) {
                return false;
            }
        }
        return true;
    }

    public static final class Builder {
        private LocalDate fromDate;
        private LocalDate toDate;
        private TransactionType type;
        private TransactionStatus status;
        private String keyword;
        private int limit;

        public Builder fromDate(LocalDate fromDate) {
            this.fromDate = fromDate;
            return this;
        }

        public Builder toDate(LocalDate toDate) {
            this.toDate = toDate;
            return this;
        }

        public Builder type(TransactionType type) {
            this.type = type;
            return this;
        }

        public Builder status(TransactionStatus status) {
            this.status = status;
            return this;
        }

        public Builder keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public TransactionFilter build() {
            return new TransactionFilter(this);
        }
    }
}
