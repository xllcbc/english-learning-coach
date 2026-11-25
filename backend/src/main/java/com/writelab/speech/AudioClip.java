package com.writelab.speech;

import java.util.Arrays;
import java.util.Objects;

/**
 * A chunk of encoded audio crossing the speech seam. The mime type is kept alongside the bytes so
 * the real adapter can tell the provider which container format it received.
 */
public record AudioClip(byte[] bytes, String mimeType) {

    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public AudioClip {
        Objects.requireNonNull(bytes, "audio bytes must not be null");
        mimeType = mimeType == null || mimeType.isBlank() ? DEFAULT_MIME_TYPE : mimeType;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioClip that
                && mimeType.equals(that.mimeType)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * mimeType.hashCode() + Arrays.hashCode(bytes);
    }
}
