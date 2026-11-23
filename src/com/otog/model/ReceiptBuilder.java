package com.otog.model;

import com.otog.util.IdGenerator;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Creational Builder constructing structured {@link ReceiptComponent} trees and
 * formatted masked receipt/confirmation strings without duplicating string formatting logic.
 * Also provides a Prototype repeat-draft helper with guaranteed fresh reference generation.
 */
public class ReceiptBuilder {

    public static final int DEFAULT_LABEL_WIDTH = 14;
    private static final String THIN_DIVIDER = "----------------------------------------";

    private String bannerTitle;
    private int labelWidth = DEFAULT_LABEL_WIDTH;
    private final ReceiptSection bodySection = new ReceiptSection(null, false, true);

    /**
     * Masks private identifiers (wallet IDs, user IDs, mobile numbers, account numbers)
     * so receipts and exports never expose raw account identifiers.
     */
    public static String maskIdentifier(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "N/A";
        }
        String trimmed = raw.trim();
        if (trimmed.contains("***") || trimmed.contains("\u2022\u2022")) {
            return trimmed;
        }
        if (trimmed.length() <= 4) {
            return "***";
        }
        int prefixLen = Math.min(4, trimmed.length() / 2);
        int suffixLen = Math.min(3, trimmed.length() - prefixLen);
        return trimmed.substring(0, prefixLen) + "***" + trimmed.substring(trimmed.length() - suffixLen);
    }

    /**
     * Prototype-style repeat transaction draft helper.
     * Clones any {@link TransactionPrototype} into a fresh {@code PENDING} draft with a guaranteed
     * fresh reference number (auto-generated when null/blank) and cleared settlement metadata.
     */
    public static Transaction createRepeatDraft(TransactionPrototype prototype, String newReferenceNumber) {
        if (prototype == null) {
            throw new IllegalArgumentException("Prototype transaction cannot be null");
        }
        String effectiveRef = (newReferenceNumber != null && !newReferenceNumber.trim().isEmpty())
                ? newReferenceNumber.trim()
                : IdGenerator.nextReferenceNumber();
        if (prototype instanceof Transaction original
                && original.getReferenceNumber() != null
                && original.getReferenceNumber().equalsIgnoreCase(effectiveRef)) {
            throw new IllegalArgumentException(
                    "Repeat transaction draft must use a fresh reference number distinct from " + effectiveRef);
        }
        return prototype.copyAsDraft(effectiveRef);
    }

    /**
     * Builds a pre-populated {@link ReceiptBuilder} for any {@link Transaction} subclass,
     * formatting amount, fee, total, reference, counterparty, timestamp, and masked identifiers.
     */
    public static ReceiptBuilder forTransaction(Transaction txn,
                                                String viewingWalletId,
                                                String counterpartyDisplay) {
        if (txn == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        ReceiptBuilder builder = new ReceiptBuilder()
                .banner("OTOG E-WALLET RECEIPT")
                .line("Txn ID", txn.getTransactionId())
                .line("Reference", txn.getReferenceNumber())
                .optionalLine("Provider Ref", txn.getProviderReference());
        txn.appendSubtypeReceiptLines(builder);
        String resolvedCounterparty = resolveCounterpartyDisplay(txn, viewingWalletId, counterpartyDisplay);
        builder.line("Type", txn.getType())
                .line("Status", txn.getStatus())
                .optionalLine("Prov Status", txn.getProviderStatus())
                .timestampLine("Date", txn.getCreatedAt())
                .optionalTimestampLine("Completed", txn.getCompletedAt())
                .counterpartyLine(resolvedCounterparty)
                .maskedLine("Source", txn.getSourceWalletId())
                .maskedLine("Destination", txn.getTargetWalletId())
                .moneyLine("Amount", txn.getAmount(), txn.getCurrency())
                .moneyLine("Fee", txn.getFee(), txn.getCurrency())
                .divider()
                .moneyLine("Total", txn.getTotalAmount(), txn.getCurrency())
                .optionalLine("Note", txn.getDescription());
        return builder;
    }

    private static String resolveCounterpartyDisplay(Transaction txn,
                                                     String viewingWalletId,
                                                     String explicitCounterparty) {
        if (explicitCounterparty != null && !explicitCounterparty.trim().isEmpty()) {
            return explicitCounterparty.trim();
        }
        if (txn instanceof CashInOutTransaction cio) {
            if (cio.getChannel() != null && !cio.getChannel().trim().isEmpty()) {
                return cio.getChannel().trim();
            }
            return "External Bank / OTC";
        }
        if (txn instanceof BillPaymentTransaction bpt) {
            if (bpt.getBillerName() != null && !bpt.getBillerName().trim().isEmpty()) {
                return bpt.getBillerName().trim();
            }
            return "Biller";
        }
        if (txn instanceof SendMoneyTransaction smt) {
            if (viewingWalletId != null && viewingWalletId.equals(txn.getTargetWalletId())
                    && txn.getSourceWalletId() != null) {
                return "Wallet " + maskIdentifier(txn.getSourceWalletId());
            }
            if (smt.getRecipientNumber() != null && !smt.getRecipientNumber().trim().isEmpty()) {
                return maskIdentifier(smt.getRecipientNumber());
            }
            if (txn.getTargetWalletId() != null) {
                return "Wallet " + maskIdentifier(txn.getTargetWalletId());
            }
        }
        if (txn instanceof InvestMoneyTransaction inv && inv.getProduct() != null && !inv.getProduct().trim().isEmpty()) {
            return inv.getProduct().trim();
        }
        if (txn instanceof LoanTransaction) {
            return "OTOG Lending";
        }
        String otherWallet = (viewingWalletId != null && viewingWalletId.equals(txn.getSourceWalletId()))
                ? txn.getTargetWalletId()
                : txn.getSourceWalletId();
        return otherWallet != null ? "Wallet " + maskIdentifier(otherWallet) : null;
    }

    public ReceiptBuilder banner(String bannerTitle) {
        this.bannerTitle = bannerTitle;
        return this;
    }

    public ReceiptBuilder labelWidth(int labelWidth) {
        this.labelWidth = Math.max(8, labelWidth);
        return this;
    }

    public ReceiptBuilder line(String label, Object value) {
        bodySection.add(new ReceiptLine(label, Objects.toString(value, "")));
        return this;
    }

    public ReceiptBuilder optionalLine(String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            bodySection.add(new ReceiptLine(label, value));
        }
        return this;
    }

    public ReceiptBuilder maskedLine(String label, String rawIdentifier) {
        if (rawIdentifier != null && !rawIdentifier.trim().isEmpty()) {
            bodySection.add(new ReceiptLine(label, maskIdentifier(rawIdentifier)));
        }
        return this;
    }

    public ReceiptBuilder counterpartyLine(String counterparty) {
        return optionalLine("Counterparty", counterparty);
    }

    public ReceiptBuilder timestampLine(String label, LocalDateTime timestamp) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getTimestampPattern());
        bodySection.add(new ReceiptLine(label, timestamp != null ? timestamp.format(dtf) : "N/A"));
        return this;
    }

    public ReceiptBuilder optionalTimestampLine(String label, LocalDateTime timestamp) {
        if (timestamp != null) {
            timestampLine(label, timestamp);
        }
        return this;
    }

    public ReceiptBuilder moneyLine(String label, BigDecimal amount, String currency) {
        bodySection.add(new ReceiptLine(label, MoneyUtil.format(amount, currency)));
        return this;
    }

    public ReceiptBuilder divider() {
        bodySection.add(new ReceiptLine(null, THIN_DIVIDER));
        return this;
    }

    public ReceiptBuilder section(ReceiptSection nestedSection) {
        if (nestedSection != null) {
            bodySection.add(nestedSection);
        }
        return this;
    }

    public ReceiptComponent buildComponent() {
        ReceiptSection root = new ReceiptSection(bannerTitle, false, false);
        root.add(bodySection);
        return root;
    }

    public String build() {
        return buildComponent().renderToString(labelWidth);
    }
}
