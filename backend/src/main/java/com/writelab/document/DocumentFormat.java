package com.writelab.document;

import java.util.Locale;
import java.util.Optional;

/**
 * The only two accepted User Document formats. The extension is the format contract; the file name
 * is never used to build a path.
 */
public enum DocumentFormat {
    MARKDOWN,
    TXT;

    public static Optional<DocumentFormat> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) {
            return Optional.of(MARKDOWN);
        }
        if (lower.endsWith(".txt")) {
            return Optional.of(TXT);
        }
        return Optional.empty();
    }
}
