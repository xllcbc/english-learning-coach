package com.writelab.document;

/**
 * A rejected User Document operation with a stable error code. Messages never contain document
 * content or file-system paths.
 */
public class UserDocumentException extends RuntimeException {

    private final DocumentErrorCode code;

    public UserDocumentException(DocumentErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public UserDocumentException(DocumentErrorCode code, Throwable cause) {
        super(code.message(), cause);
        this.code = code;
    }

    public DocumentErrorCode code() {
        return code;
    }
}
