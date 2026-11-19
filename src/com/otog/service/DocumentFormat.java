package com.otog.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Structural Bridge Implementor decoupling document persistence format ({@code .txt} pipe format
 * vs. {@code .csv}) from {@link ExportableDocument} abstractions ({@link WalletStatementDocument},
 * {@link TransactionReceiptDocument}).
 */
public interface DocumentFormat {

    /**
     * Immutable data carrier for serializing a masked wallet statement.
     */
    record StatementData(String version,
                         String exportedAt,
                         String maskedOwner,
                         String maskedWalletId,
                         String currency,
                         BigDecimal balance,
                         List<StatementFileService.StatementEntry> entries) {
    }

    /**
     * Immutable data carrier for serializing a masked transaction receipt.
     */
    record ReceiptData(String referenceNumber,
                       String maskedSourceWallet,
                       String maskedTargetWallet,
                       String receiptBody) {
    }

    String getFileExtension();

    String getStatementHeader();

    String getReceiptHeader();

    void writeStatement(StatementData data, BufferedWriter writer) throws IOException;

    StatementFileService.LoadedStatement readStatement(BufferedReader reader) throws IOException;

    void writeReceipt(ReceiptData data, BufferedWriter writer) throws IOException;

    String readReceipt(BufferedReader reader) throws IOException;
}
