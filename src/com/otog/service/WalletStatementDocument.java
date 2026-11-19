package com.otog.service;

import com.otog.exception.WalletException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Refined Bridge Abstraction handling wallet statement persistence and loading
 * across any {@link DocumentFormat} implementation.
 */
public class WalletStatementDocument extends ExportableDocument {

    public WalletStatementDocument(DocumentFormat format) {
        super(format);
    }

    public Path exportTo(DocumentFormat.StatementData data, Path targetFile) {
        if (data == null) {
            throw new WalletException("Cannot export statement: statement data is required.");
        }
        if (targetFile == null) {
            throw new WalletException("Cannot export statement: target file path is required.");
        }
        try {
            ensureParentDirectory(targetFile);
            try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
                format.writeStatement(data, writer);
            }
            return targetFile;
        } catch (IOException ex) {
            throw new WalletException("Failed to write wallet statement file: " + ex.getMessage(), ex);
        }
    }

    public StatementFileService.LoadedStatement importFrom(Path sourceFile) {
        requireExistingFile(sourceFile, "Statement");
        try (BufferedReader reader = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8)) {
            return format.readStatement(reader);
        } catch (IOException ex) {
            throw new WalletException("Failed to read wallet statement file: " + ex.getMessage(), ex);
        }
    }
}
