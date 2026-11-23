package com.writelab.document;

/**
 * Processing failed after upload. The error code is persisted on the User Document so the panel can
 * explain what went wrong; the document does not participate in retrieval while it is failed.
 */
public class DocumentProcessingException extends RuntimeException {

    private final DocumentErrorCode code;

    public DocumentProcessingException(DocumentErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public DocumentProcessingException(DocumentErrorCode code, Throwable cause) {
        super(code.message(), cause);
        this.code = code;
    }

    public DocumentErrorCode code() {
        return code;
    }
}
