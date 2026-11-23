package com.otog.service;

import com.otog.exception.WalletException;
import com.otog.model.ReceiptBuilder;
import com.otog.model.Transaction;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Provides file-handling capabilities (CSIT227 File Handling: store and load data)
 * via the Structural Bridge pattern ({@link ExportableDocument} + {@link DocumentFormat}),
 * supporting both masked pipe-delimited text ({@code .txt}) and CSV ({@code .csv}) formats
 * using {@code try-with-resources} {@code BufferedWriter} and {@code BufferedReader} streams.
 */
public class StatementFileService {

    public static final String STATEMENT_HEADER = PipeTextDocumentFormat.STATEMENT_HEADER;
    public static final String RECEIPT_HEADER = PipeTextDocumentFormat.RECEIPT_HEADER;

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
            sb.append("Format       : ").append(version).append("\n");
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
                            PipeTextDocumentFormat.formatPlain(entry.getAmount()),
                            PipeTextDocumentFormat.formatPlain(entry.getFee()),
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
        return ReceiptBuilder.maskIdentifier(raw);
    }

    public static DocumentFormat resolveFormatForPath(Path file) {
        if (file != null && file.getFileName() != null) {
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".csv")) {
                return new CsvDocumentFormat();
            }
        }
        return new PipeTextDocumentFormat();
    }

    /**
     * Stores a masked wallet statement and transaction history to disk using the Bridge format
     * inferred from the target file extension ({@code .csv} or pipe-delimited {@code .txt}).
     */
    public Path exportWalletStatement(User user,
                                      Wallet wallet,
                                      List<Transaction> transactions,
                                      Path targetFile) {
        return exportWalletStatement(user, wallet, transactions, targetFile, resolveFormatForPath(targetFile));
    }

    public Path exportWalletStatement(User user,
                                      Wallet wallet,
                                      List<Transaction> transactions,
                                      Path targetFile,
                                      DocumentFormat format) {
        if (wallet == null) {
            throw new WalletException("Cannot export statement: wallet is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export statement: target file path is required.");
        }
        DocumentFormat effectiveFormat = format != null ? format : resolveFormatForPath(targetFile);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(OtogConfig.getInstance().getTimestampPattern());
        List<Transaction> safeTxns = transactions != null ? transactions : Collections.emptyList();

        String ownerDisplay = user != null
                ? user.getName() + " (" + maskIdentifier(user.getMobileNumber()) + ")"
                : maskIdentifier(wallet.getUserId());
        String maskedWallet = maskIdentifier(wallet.getWalletId());
        String exportedAt = LocalDateTime.now().format(formatter);

        List<StatementEntry> entries = new ArrayList<>();
        for (Transaction txn : safeTxns) {
            if (txn == null) {
                continue;
            }
            String ts = txn.getCreatedAt() != null
                    ? txn.getCreatedAt().format(formatter)
                    : exportedAt;
            String direction = resolveDirectionForWallet(wallet.getWalletId(), txn);
            String providerRef = txn.getProviderReference() != null && !txn.getProviderReference().trim().isEmpty()
                    ? txn.getProviderReference().trim()
                    : "-";
            String desc = txn.getDescription() != null ? txn.getDescription() : "";
            entries.add(new StatementEntry(
                    ts,
                    txn.getReferenceNumber(),
                    txn.getType(),
                    direction,
                    txn.getAmount(),
                    txn.getFee(),
                    txn.getStatus(),
                    providerRef,
                    desc
            ));
        }

        DocumentFormat.StatementData data = new DocumentFormat.StatementData(
                effectiveFormat.getStatementHeader(),
                exportedAt,
                ownerDisplay,
                maskedWallet,
                wallet.getCurrency(),
                wallet.getBalance(),
                entries
        );
        return new WalletStatementDocument(effectiveFormat).exportTo(data, targetFile);
    }

    /**
     * Loads and validates a saved OTOG wallet statement file from disk using BufferedReader.
     */
    public LoadedStatement importWalletStatement(Path sourceFile) {
        return importWalletStatement(sourceFile, resolveFormatForPath(sourceFile));
    }

    public LoadedStatement importWalletStatement(Path sourceFile, DocumentFormat format) {
        DocumentFormat effectiveFormat = format != null ? format : resolveFormatForPath(sourceFile);
        return new WalletStatementDocument(effectiveFormat).importFrom(sourceFile);
    }

    /**
     * Stores a single transaction receipt to a file using BufferedWriter via the Bridge pattern.
     */
    public Path exportReceipt(Transaction transaction, Path targetFile) {
        return exportReceipt(transaction, targetFile, resolveFormatForPath(targetFile));
    }

    public Path exportReceipt(Transaction transaction, Path targetFile, DocumentFormat format) {
        if (transaction == null) {
            throw new WalletException("Cannot export receipt: transaction is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export receipt: target file path is required.");
        }
        DocumentFormat effectiveFormat = format != null ? format : resolveFormatForPath(targetFile);
        DocumentFormat.ReceiptData data = new DocumentFormat.ReceiptData(
                transaction.getReferenceNumber(),
                maskIdentifier(transaction.getSourceWalletId()),
                maskIdentifier(transaction.getTargetWalletId()),
                transaction.getReceipt()
        );
        return new TransactionReceiptDocument(effectiveFormat).exportTo(data, targetFile);
    }

    /**
     * Loads and validates a saved transaction receipt file from disk using BufferedReader.
     */
    public String importReceipt(Path sourceFile) {
        return importReceipt(sourceFile, resolveFormatForPath(sourceFile));
    }

    public String importReceipt(Path sourceFile, DocumentFormat format) {
        DocumentFormat effectiveFormat = format != null ? format : resolveFormatForPath(sourceFile);
        return new TransactionReceiptDocument(effectiveFormat).importFrom(sourceFile);
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
}
