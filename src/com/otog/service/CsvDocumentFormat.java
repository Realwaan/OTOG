package com.otog.service;

import com.otog.exception.InvalidAmountException;
import com.otog.exception.OtogException;
import com.otog.exception.WalletException;
import com.otog.model.TransactionStatus;
import com.otog.model.TransactionType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete Bridge Implementor reading and writing masked RFC-4180 CSV statements
 * ({@code OTOG-STATEMENT-CSV-V1}) and receipts ({@code OTOG-RECEIPT-CSV-V1}).
 */
public class CsvDocumentFormat implements DocumentFormat {

    public static final String STATEMENT_HEADER = "OTOG-STATEMENT-CSV-V1";
    public static final String RECEIPT_HEADER = "OTOG-RECEIPT-CSV-V1";
    private static final String COLUMN_HEADER = "timestamp,reference_number,type,direction,amount,fee,status,provider_reference,description";

    @Override
    public String getFileExtension() {
        return "csv";
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
        writer.write(csvRow("EXPORTED_AT", data.exportedAt()));
        writer.newLine();
        writer.write(csvRow("OWNER", data.maskedOwner()));
        writer.newLine();
        writer.write(csvRow("WALLET_ID", data.maskedWalletId()));
        writer.newLine();
        writer.write(csvRow("CURRENCY", data.currency()));
        writer.newLine();
        writer.write(csvRow("BALANCE", PipeTextDocumentFormat.formatPlain(data.balance())));
        writer.newLine();
        writer.write(csvRow("COUNT", Integer.toString(data.entries().size())));
        writer.newLine();
        writer.write(COLUMN_HEADER);
        writer.newLine();

        for (StatementFileService.StatementEntry entry : data.entries()) {
            writer.write(csvRow(
                    entry.getTimestamp(),
                    entry.getReferenceNumber(),
                    entry.getType().name(),
                    entry.getDirection(),
                    PipeTextDocumentFormat.formatPlain(entry.getAmount()),
                    PipeTextDocumentFormat.formatPlain(entry.getFee()),
                    entry.getStatus().name(),
                    entry.getProviderReference(),
                    entry.getDescription()
            ));
            writer.newLine();
        }
    }

    @Override
    public StatementFileService.LoadedStatement readStatement(BufferedReader reader) throws IOException {
        String firstLine = reader.readLine();
        if (firstLine == null || !STATEMENT_HEADER.equals(firstLine.trim())) {
            throw new WalletException("Invalid or unsupported CSV statement file header.");
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        List<StatementFileService.StatementEntry> entries = new ArrayList<>();
        boolean inRows = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (!inRows && COLUMN_HEADER.equals(line.trim())) {
                inRows = true;
                continue;
            }
            List<String> cols = parseCsvLine(line);
            if (!inRows) {
                if (cols.size() < 2) {
                    throw new WalletException("Malformed CSV metadata line: " + line);
                }
                metadata.put(cols.get(0).trim(), cols.get(1).trim());
            } else {
                if (cols.size() < 9) {
                    throw new WalletException("Incomplete CSV transaction fields: " + line);
                }
                try {
                    entries.add(new StatementFileService.StatementEntry(
                            cols.get(0),
                            cols.get(1),
                            TransactionType.valueOf(cols.get(2)),
                            cols.get(3),
                            new BigDecimal(cols.get(4)),
                            new BigDecimal(cols.get(5)),
                            TransactionStatus.valueOf(cols.get(6)),
                            cols.get(7),
                            cols.get(8)
                    ));
                } catch (IllegalArgumentException ex) {
                    throw new InvalidAmountException("Corrupted CSV transaction row: " + ex.getMessage());
                }
            }
        }

        return PipeTextDocumentFormat.buildValidatedStatement(STATEMENT_HEADER, metadata, entries);
    }

    @Override
    public void writeReceipt(ReceiptData data, BufferedWriter writer) throws IOException {
        writer.write(RECEIPT_HEADER);
        writer.newLine();
        writer.write("field,value");
        writer.newLine();
        writer.write(csvRow("REF", data.referenceNumber()));
        writer.newLine();
        writer.write(csvRow("SOURCE_WALLET", data.maskedSourceWallet()));
        writer.newLine();
        writer.write(csvRow("DEST_WALLET", data.maskedTargetWallet()));
        writer.newLine();
        writer.write(csvRow("RECEIPT_BODY", data.receiptBody() != null ? data.receiptBody() : ""));
        writer.newLine();
    }

    @Override
    public String readReceipt(BufferedReader reader) throws IOException {
        String firstLine = reader.readLine();
        if (firstLine == null || !RECEIPT_HEADER.equals(firstLine.trim())) {
            throw new WalletException("Invalid or unsupported CSV receipt file header.");
        }
        StringBuilder content = new StringBuilder();
        boolean foundBody = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!foundBody) {
                List<String> cols = parseCsvLine(line);
                if (cols.size() >= 2 && "RECEIPT_BODY".equals(cols.get(0).trim())) {
                    content.append(cols.get(1));
                    foundBody = true;
                }
            } else {
                content.append('\n').append(stripTrailingQuote(line));
            }
        }
        if (content.isEmpty()) {
            throw new OtogException("CSV receipt file contains no receipt body.");
        }
        return content.toString();
    }

    private static String stripTrailingQuote(String line) {
        if (line.endsWith("\"") && !line.endsWith("\"\"")) {
            return line.substring(0, line.length() - 1).replace("\"\"", "\"");
        }
        return line.replace("\"\"", "\"");
    }

    private static String csvRow(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(values[i]));
        }
        return sb.toString();
    }

    private static String escapeCsv(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        String cleaned = raw.replace("\r\n", "\n").replace("\r", "\n");
        if (cleaned.contains(",") || cleaned.contains("\"") || cleaned.contains("\n")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
