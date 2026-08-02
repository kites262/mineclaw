package cc.kites.mineclaw.api;

/** A sanitized API failure. Response bodies and authorization credentials are never included. */
public final class ChatCompletionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final boolean retryable;

    ChatCompletionException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    ChatCompletionException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
