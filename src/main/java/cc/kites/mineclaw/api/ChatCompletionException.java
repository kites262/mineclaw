package cc.kites.mineclaw.api;

/** A Provider failure retaining the upstream response for server-side diagnostics. */
public final class ChatCompletionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final boolean retryable;
    private final String requestId;
    private final String responseBody;

    ChatCompletionException(String message, int statusCode, boolean retryable) {
        this(message, statusCode, retryable, "", "", null);
    }

    ChatCompletionException(String message, int statusCode, boolean retryable, Throwable cause) {
        this(message, statusCode, retryable, "", "", cause);
    }

    ChatCompletionException(String message, int statusCode, boolean retryable, String requestId,
                            String responseBody) {
        this(message, statusCode, retryable, requestId, responseBody, null);
    }

    private ChatCompletionException(String message, int statusCode, boolean retryable, String requestId,
                                    String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.requestId = safe(requestId);
        this.responseBody = safe(responseBody);
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String requestId() { return requestId; }

    public String responseBody() { return responseBody; }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
