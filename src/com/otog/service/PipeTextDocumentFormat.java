package com.otog.service;

import com.otog.exception.InvalidAmountException;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;
import com.otog.util.MoneyUtil;
import com.otog.util.OtogConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete Bridge Implementor reading and writing masked pipe-delimited OTOG statements
 * ({@code OTOG-STATEMENT-V1}) and transaction receipts ({@code OTOG-RECEIPT-V1}).
 */
public class PipeTextDocumentFormat implements DocumentFormat {

    public static final String STATEMENT_HEADER = "OTOG-STATEMENT-V1";
    public static final String RECEIPT_HEADER = "OTOG-RECEIPT-V1";
    private static final String TRANSACTIONS_MARKER = "---TRANSACTIONS---";
    private static final String RECEIPT_MARKER = "---RECEIPT---";

    @Override
    public String getFileExtension() {
        return "txt";
    }

    @Override
    public String getStatementHeader() {
        return STATEMENT_HEADER;
    }

    @Override
    public String getReceiptHeader() {
        return RECEIPT_HEADER;
    }

    @Override
    public void writeStatement(StatementData data, BufferedWriter writer) throws IOException {
        writer.write(STATEMENT_HEADER);
        writer.newLine();
        writer.write("EXPORTED_AT=" + sanitizeField(data.exportedAt()));
        writer.newLine();
        writer.write("OWNER=" + sanitizeField(data.maskedOwner()));
        writer.newLine();
        writer.write("WALLET_ID=" + sanitizeField(data.maskedWalletId()));
        writer.newLine();
        writer.write("CURRENCY=" + sanitizeField(data.currency()));
        writer.newLine();
        writer.write("BALANCE=" + formatPlain(data.balance()));
        writer.newLine();
        writer.write("COUNT=" + data.entries().size());
        writer.newLine();
        writer.write(TRANSACTIONS_MARKER);
        writer.newLine();

        for (StatementFileService.StatementEntry entry : data.entries()) {
            String row = String.join("|",
                    sanitizeField(entry.getTimestamp()),
                    sanitizeField(entry.getReferenceNumber()),
                    entry.getType().name(),
                    sanitizeField(entry.getDirection()),
                    formatPlain(entry.getAmount()),
                    formatPlain(entry.getFee()),
                    entry.getStatus().name(),
                    sanitizeField(entry.getProviderReference()),
                    sanitizeField(entry.getDescription())
            );
            writer.write("TXN|" + row);
            writer.newLine();
        }
    }

    @Override
    public StatementFileService.LoadedStatement readStatement(BufferedReader reader) throws IOException {
        String firstLine = reader.readLine();
        if (firstLine == null || !STATEMENT_HEADER.equals(firstLine.trim())) {
            throw new WalletException("Invalid or unsupported statement file header.");
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        List<StatementFileService.StatementEntry> entries = new ArrayList<>();
        boolean inTransactions = false;

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (TRANSACTIONS_MARKER.equals(trimmed)) {
                inTransactions = true;
                continue;
            }
            if (!inTransactions) {
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    throw new WalletException("Malformed metadata line in statement file: " + trimmed);
                }
                metadata.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            } else {
                if (!trimmed.startsWith("TXN|")) {
                    throw new WalletException("Malformed transaction row in statement file: " + trimmed);
                }
                String[] parts = trimmed.split("\\|", -1);
                if (parts.length < 10) {
                    throw new WalletException("Incomplete transaction fields in statement row: " + trimmed);
                }
                try {
                    entries.add(new StatementFileService.StatementEntry(
                            parts[1],
                            parts[2],
                            TransactionType.valueOf(parts[3]),
                            parts[4],
                            new BigDecimal(parts[5]),
                            new BigDecimal(parts[6]),
                            TransactionStatus.valueOf(parts[7]),
                            parts[8],
                            parts[9]
                    ));
                } catch (IllegalArgumentException ex) {
                    throw new InvalidAmountException("Corrupted transaction entry in statement file: " + ex.getMessage());
                }
            }
        }

        return buildValidatedStatement(STATEMENT_HEADER, metadata, entries);
    }

    @Override
    public void writeReceipt(ReceiptData data, BufferedWriter writer) throws IOException {
        writer.write(RECEIPT_HEADER);
        writer.newLine();
        writer.write("REF=" + sanitizeField(data.referenceNumber()));
        writer.newLine();
        writer.write("SOURCE_WALLET=" + sanitizeField(data.maskedSourceWallet()));
        writer.newLine();
        writer.write("DEST_WALLET=" + sanitizeField(data.maskedTargetWallet()));
        writer.newLine();
        writer.write(RECEIPT_MARKER);
        writer.newLine();
        writer.write(data.receiptBody() != null ? data.receiptBody() : "");
        writer.newLine();
    }

    @Override
    public String readReceipt(BufferedReader reader) throws IOException {
        String firstLine = reader.readLine();
        if (firstLine == null || !RECEIPT_HEADER.equals(firstLine.trim())) {
            throw new WalletException("Invalid or unsupported receipt file header.");
        }
        StringBuilder content = new StringBuilder();
        String line;
        boolean inReceiptBody = false;
        while ((line = reader.readLine()) != null) {
            if (RECEIPT_MARKER.equals(line.trim())) {
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
        if (content.length() == 0) {
            throw new OtogException("Receipt file contains no receipt body.");
        }
        return content.toString();
    }

    static StatementFileService.LoadedStatement buildValidatedStatement(String header,
                                                                      Map<String, String> metadata,
                                                                      List<StatementFileService.StatementEntry> entries) {
        String exportedAt = metadata.getOrDefault("EXPORTED_AT", "Unknown");
        String owner = metadata.getOrDefault("OWNER", "Unknown");
        String walletId = metadata.getOrDefault("WALLET_ID", "Unknown");
        String currency = metadata.getOrDefault("CURRENCY", OtogConfig.getInstance().getDefaultCurrency());
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
        return new StatementFileService.LoadedStatement(header, exportedAt, owner, walletId, currency, balance, entries);
    }

    static String formatPlain(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(MoneyUtil.DEFAULT_SCALE, MoneyUtil.DEFAULT_ROUNDING).toPlainString();
    }

    static String sanitizeField(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("|", "/").replace("\r", " ").replace("\n", " ").trim();
    }
}
