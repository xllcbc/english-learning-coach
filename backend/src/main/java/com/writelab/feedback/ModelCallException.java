package com.writelab.feedback;

/**
 * Thrown when the real model call itself fails (transport, auth, upstream error) rather than
 * returning a payload that fails validation. These are not retried here: the underlying OpenAI SDK
 * already retries transient failures, and retrying auth/4xx errors would only resend the request.
 */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
