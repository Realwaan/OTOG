package com.otog.service;

import com.otog.exception.WalletException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Refined Bridge Abstraction handling transaction receipt export and import
 * across any {@link DocumentFormat} implementation.
 */
public class TransactionReceiptDocument extends ExportableDocument {

    public TransactionReceiptDocument(DocumentFormat format) {
        super(format);
    }

    public Path exportTo(DocumentFormat.ReceiptData data, Path targetFile) {
        if (data == null) {
            throw new WalletException("Cannot export receipt: receipt data is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export receipt: target file path is required.");
        }
        try {
            ensureParentDirectory(targetFile);
            try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
                format.writeReceipt(data, writer);
            }
            return targetFile;
        } catch (IOException ex) {
            throw new WalletException("Failed to write receipt file: " + ex.getMessage(), ex);
        }
    }

    public String importFrom(Path sourceFile) {
        requireExistingFile(sourceFile, "Receipt");
        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            return format.readReceipt(reader);
        } catch (IOException ex) {
            throw new WalletException("Failed to read receipt file: " + ex.getMessage(), ex);
        }
    }
}
