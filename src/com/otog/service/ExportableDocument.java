package com.otog.service;

import com.otog.exception.WalletException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Structural Bridge Abstraction representing a file-persisted document whose serialization
 * implementation is delegated to a pluggable {@link DocumentFormat}.
 */
public abstract class ExportableDocument {

    protected final DocumentFormat format;

    protected ExportableDocument(DocumentFormat format) {
        this.format = Objects.requireNonNull(format, "DocumentFormat bridge implementor cannot be null");
    }

    public DocumentFormat getFormat() {
        return format;
    }

    protected static void ensureParentDirectory(Path targetFile) throws IOException {
        Path parent = targetFile.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    protected static void requireExistingFile(Path sourceFile, String label) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new WalletException(label + " file does not exist: " + sourceFile);
        }
    }
}
