package com.otog.service;

import com.otog.exception.InvalidAmountException;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.util.MoneyUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides file-handling capabilities (CSIT227 File Handling: store and load data)
 * for exporting and importing masked wallet statements and transaction receipts
 * using try-with-resources BufferedWriter and BufferedReader streams.
 */
public class StatementFileService {

    public static final String STATEMENT_HEADER = "OTOG-STATEMENT-V1";
    public static final String RECEIPT_HEADER = "OTOG-RECEIPT-V1";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Encapsulated immutable record representing a single transaction entry loaded from a statement file.
     */
    public static final class StatementEntry {
        private final String timestamp;
        private final String referenceNumber;
        private final TransactionType type;
        private final String direction;
        private final BigDecimal amount;
        private final BigDecimal fee;
        private final TransactionStatus status;
        private final String providerReference;
        private final String description;

        public StatementEntry(String timestamp,
                              String referenceNumber,
                              TransactionType type,
                              String direction,
                              BigDecimal amount,
                              BigDecimal fee,
                              TransactionStatus status,
                              String providerReference,
                              String description) {
            this.timestamp = timestamp != null ? timestamp : "";
            this.referenceNumber = referenceNumber != null ? referenceNumber : "";
            this.type = type != null ? type : TransactionType.SEND_MONEY;
            this.direction = direction != null ? direction : "N/A";
            this.amount = MoneyUtil.validateNonNegativeAmount(amount != null ? amount : BigDecimal.ZERO);
            this.fee = MoneyUtil.validateNonNegativeAmount(fee != null ? fee : BigDecimal.ZERO);
            this.status = status != null ? status : TransactionStatus.COMPLETED;
            this.providerReference = providerReference != null ? providerReference : "-";
            this.description = description != null ? description : "";
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getReferenceNumber() {
            return referenceNumber;
        }

        public TransactionType getType() {
            return type;
        }

        public String getDirection() {
            return direction;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public BigDecimal getFee() {
            return fee;
        }

        public TransactionStatus getStatus() {
            return status;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Encapsulated immutable model representing a wallet statement loaded from disk.
     */
    public static final class LoadedStatement {
        private final String version;
        private final String exportedAt;
        private final String maskedOwner;
        private final String maskedWalletId;
        private final String currency;
        private final BigDecimal balance;
        private final List<StatementEntry> entries;

        public LoadedStatement(String version,
                               String exportedAt,
                               String maskedOwner,
                               String maskedWalletId,
                               String currency,
                               BigDecimal balance,
                               List<StatementEntry> entries) {
            this.version = version;
            this.exportedAt = exportedAt;
            this.maskedOwner = maskedOwner;
            this.maskedWalletId = maskedWalletId;
            this.currency = currency;
            this.balance = MoneyUtil.validateNonNegativeAmount(balance);
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        public String getVersion() {
            return version;
        }

        public String getExportedAt() {
            return exportedAt;
        }

        public String getMaskedOwner() {
            return maskedOwner;
        }

        public String getMaskedWalletId() {
            return maskedWalletId;
        }

        public String getCurrency() {
            return currency;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public List<StatementEntry> getEntries() {
            return entries;
        }

        public String toDisplaySummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("==================================================\n");
            sb.append("         OTOG E-WALLET LOADED STATEMENT           \n");
            sb.append("==================================================\n");
            sb.append("Exported At  : ").append(exportedAt).append("\n");
            sb.append("Account Owner: ").append(maskedOwner).append("\n");
            sb.append("Wallet ID    : ").append(maskedWalletId).append("\n");
            sb.append("Balance      : ").append(MoneyUtil.format(balance, currency)).append("\n");
            sb.append("Transactions : ").append(entries.size()).append("\n");
            sb.append("--------------------------------------------------\n");
            if (entries.isEmpty()) {
                sb.append("(No transactions recorded in this statement)\n");
            } else {
                for (StatementEntry entry : entries) {
                    sb.append(String.format("%s | %-14s | %-12s | %-3s | %10s | Fee %6s | %-9s | %s%n",
                            entry.getTimestamp(),
                            entry.getReferenceNumber(),
                            entry.getType().name(),
                            entry.getDirection(),
                            formatPlain(entry.getAmount()),
                            formatPlain(entry.getFee()),
                            entry.getStatus().name(),
                            entry.getDescription()));
                }
            }
            sb.append("==================================================");
            return sb.toString();
        }
    }

    /**
     * Masks private identifiers (wallet IDs, user IDs, mobile numbers) so exported files
     * never expose raw private account identifiers.
     */
    public static String maskIdentifier(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "N/A";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= 4) {
            return "***";
        }
        int prefixLen = Math.min(4, trimmed.length() / 2);
        int suffixLen = Math.min(3, trimmed.length() - prefixLen);
        return trimmed.substring(0, prefixLen) + "***" + trimmed.substring(trimmed.length() - suffixLen);
    }

    /**
     * Stores a masked wallet statement and transaction history to disk using BufferedWriter.
     */
    public Path exportWalletStatement(User user,
                                      Wallet wallet,
                                      List<Transaction> transactions,
                                      Path targetFile) {
        if (wallet == null) {
            throw new WalletException("Cannot export statement: wallet is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export statement: target file path is required.");
        }
        List<Transaction> safeTxns = transactions != null ? transactions : Collections.emptyList();
        String ownerDisplay = user != null
                ? user.getName() + " (" + maskIdentifier(user.getMobileNumber()) + ")"
                : maskIdentifier(wallet.getUserId());
        String maskedWallet = maskIdentifier(wallet.getWalletId());
        String exportedAt = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        try {
            Path parent = targetFile.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
                writer.write(STATEMENT_HEADER);
                writer.newLine();
                writer.write("EXPORTED_AT=" + sanitizeField(exportedAt));
                writer.newLine();
                writer.write("OWNER=" + sanitizeField(ownerDisplay));
                writer.newLine();
                writer.write("WALLET_ID=" + sanitizeField(maskedWallet));
                writer.newLine();
                writer.write("CURRENCY=" + sanitizeField(wallet.getCurrency()));
                writer.newLine();
                writer.write("BALANCE=" + formatPlain(wallet.getBalance()));
                writer.newLine();
                writer.write("COUNT=" + safeTxns.size());
                writer.newLine();
                writer.write("---TRANSACTIONS---");
                writer.newLine();

                for (Transaction txn : safeTxns) {
                    if (txn == null) {
                        continue;
                    }
                    String ts = txn.getCreatedAt() != null
                            ? txn.getCreatedAt().format(TIMESTAMP_FORMATTER)
                            : exportedAt;
                    String direction = resolveDirectionForWallet(wallet.getWalletId(), txn);
                    String providerRef = txn.getProviderReference() != null && !txn.getProviderReference().trim().isEmpty()
                            ? txn.getProviderReference().trim()
                            : "-";
                    String desc = txn.getDescription() != null ? sanitizeField(txn.getDescription()) : "";
                    String line = String.join("|",
                            sanitizeField(ts),
                            sanitizeField(txn.getReferenceNumber()),
                            txn.getType().name(),
                            direction,
                            formatPlain(txn.getAmount()),
                            formatPlain(txn.getFee()),
                            txn.getStatus().name(),
                            sanitizeField(providerRef),
                            desc
                    );
                    writer.write("TXN|" + line);
                    writer.newLine();
                }
            }
            return targetFile;
        } catch (IOException ex) {
            throw new WalletException("Failed to write wallet statement file: " + ex.getMessage(), ex);
        }
    }

    /**
     * Loads and validates a saved OTOG wallet statement file from disk using BufferedReader.
     */
    public LoadedStatement importWalletStatement(Path sourceFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new WalletException("Statement file does not exist: " + sourceFile);
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        List<StatementEntry> entries = new ArrayList<>();
        boolean inTransactions = false;

        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !STATEMENT_HEADER.equals(firstLine.trim())) {
                throw new WalletException("Invalid or unsupported statement file header.");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("---TRANSACTIONS---".equals(trimmed)) {
                    inTransactions = true;
                    continue;
                }
                if (!inTransactions) {
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        throw new WalletException("Malformed metadata line in statement file: " + trimmed);
                    }
                    String key = trimmed.substring(0, eq).trim();
                    String val = trimmed.substring(eq + 1).trim();
                    metadata.put(key, val);
                } else {
                    if (!trimmed.startsWith("TXN|")) {
                        throw new WalletException("Malformed transaction row in statement file: " + trimmed);
                    }
                    String[] parts = trimmed.split("\\|", -1);
                    if (parts.length < 10) {
                        throw new WalletException("Incomplete transaction fields in statement row: " + trimmed);
                    }
                    try {
                        String ts = parts[1];
                        String ref = parts[2];
                        TransactionType type = TransactionType.valueOf(parts[3]);
                        String direction = parts[4];
                        BigDecimal amount = new BigDecimal(parts[5]);
                        BigDecimal fee = new BigDecimal(parts[6]);
                        TransactionStatus status = TransactionStatus.valueOf(parts[7]);
                        String providerRef = parts[8];
                        String desc = parts[9];
                        entries.add(new StatementEntry(ts, ref, type, direction, amount, fee, status, providerRef, desc));
                    } catch (IllegalArgumentException ex) {
                        throw new InvalidAmountException("Corrupted transaction entry in statement file: " + ex.getMessage());
                    }
                }
            }
        } catch (IOException ex) {
            throw new WalletException("Failed to read wallet statement file: " + ex.getMessage(), ex);
        }

        String exportedAt = metadata.getOrDefault("EXPORTED_AT", "Unknown");
        String owner = metadata.getOrDefault("OWNER", "Unknown");
        String walletId = metadata.getOrDefault("WALLET_ID", "Unknown");
        String currency = metadata.getOrDefault("CURRENCY", MoneyUtil.DEFAULT_CURRENCY);
        String balanceText = metadata.get("BALANCE");
        if (balanceText == null) {
            throw new WalletException("Statement file is missing required BALANCE metadata.");
        }
        BigDecimal balance;
        try {
            balance = MoneyUtil.validateNonNegativeAmount(new BigDecimal(balanceText));
        } catch (NumberFormatException ex) {
            throw new InvalidAmountException("Invalid balance value in statement file: " + balanceText);
        }

        String expectedCountText = metadata.get("COUNT");
        if (expectedCountText != null) {
            try {
                int expectedCount = Integer.parseInt(expectedCountText);
                if (expectedCount != entries.size()) {
                    throw new WalletException("Statement transaction count mismatch: expected "
                            + expectedCount + " but loaded " + entries.size());
                }
            } catch (NumberFormatException ex) {
                throw new WalletException("Invalid COUNT metadata in statement file: " + expectedCountText, ex);
            }
        }

        return new LoadedStatement(STATEMENT_HEADER, exportedAt, owner, walletId, currency, balance, entries);
    }

    /**
     * Stores a single transaction receipt to a text file using BufferedWriter.
     */
    public Path exportReceipt(Transaction transaction, Path targetFile) {
        if (transaction == null) {
            throw new WalletException("Cannot export receipt: transaction is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export receipt: target file path is required.");
        }
        try {
            Path parent = targetFile.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
                writer.write(RECEIPT_HEADER);
                writer.newLine();
                writer.write("REF=" + sanitizeField(transaction.getReferenceNumber()));
                writer.newLine();
                writer.write("SOURCE_WALLET=" + maskIdentifier(transaction.getSourceWalletId()));
                writer.newLine();
                writer.write("DEST_WALLET=" + maskIdentifier(transaction.getTargetWalletId()));
                writer.newLine();
                writer.write("---RECEIPT---");
                writer.newLine();
                writer.write(transaction.getReceipt());
                writer.newLine();
            }
            return targetFile;
        } catch (IOException ex) {
            throw new WalletException("Failed to write receipt file: " + ex.getMessage(), ex);
        }
    }

    /**
     * Loads and validates a saved transaction receipt file from disk using BufferedReader.
     */
    public String importReceipt(Path sourceFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new WalletException("Receipt file does not exist: " + sourceFile);
        }
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !RECEIPT_HEADER.equals(firstLine.trim())) {
                throw new WalletException("Invalid or unsupported receipt file header.");
            }
            String line;
            boolean inReceiptBody = false;
            while ((line = reader.readLine()) != null) {
                if ("---RECEIPT---".equals(line.trim())) {
                    inReceiptBody = true;
                    continue;
                }
                if (inReceiptBody) {
                    if (content.length() > 0) {
                        content.append("\n");
                    }
                    content.append(line);
                }
            }
        } catch (IOException ex) {
            throw new WalletException("Failed to read receipt file: " + ex.getMessage(), ex);
        }
        if (content.length() == 0) {
            throw new OtogException("Receipt file contains no receipt body.");
        }
        return content.toString();
    }

    private static String resolveDirectionForWallet(String walletId, Transaction txn) {
        if (txn.getType() == TransactionType.CASH_IN || txn.getType() == TransactionType.LOAN_DISBURSEMENT
                || txn.getType() == TransactionType.INVESTMENT_REDEEM) {
            return "IN";
        }
        if (txn.getType() == TransactionType.CASH_OUT || txn.getType() == TransactionType.BILL_PAYMENT
                || txn.getType() == TransactionType.LOAN_REPAYMENT || txn.getType() == TransactionType.INVESTMENT_BUY) {
            return "OUT";
        }
        if (walletId != null && walletId.equals(txn.getTargetWalletId())
                && !walletId.equals(txn.getSourceWalletId())) {
            return "IN";
        }
        return "OUT";
    }

    private static String formatPlain(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING).toPlainString();
    }

    private static String sanitizeField(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("|", "/").replace("\r", " ").replace("\n", " ").trim();
    }
}
