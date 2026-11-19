package com.otog.model;

/**
 * Creational Prototype contract enabling polymorphic cloning of any {@link Transaction}
 * subclass into a fresh {@code PENDING} draft transaction for repeat transfers or templates.
 */
public interface TransactionPrototype {

    /**
     * Clones this transaction into a new {@code PENDING} draft with a freshly generated
     * transaction ID, the supplied reference number (or a newly generated reference if blank),
     * current creation timestamp, and cleared settlement fields.
     *
     * @param newReferenceNumber unique idempotency reference for the new draft, or null/blank to auto-generate
     * @return a fresh draft clone preserving domain parameters without mutating the prototype instance
     */
    Transaction copyAsDraft(String newReferenceNumber);
}
