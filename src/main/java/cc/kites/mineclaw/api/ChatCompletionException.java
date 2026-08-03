package cc.kites.mineclaw.api;

/** A sanitized Provider failure whose detailed fields are safe for server-only diagnostics. */
public final class ChatCompletionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final boolean retryable;
    private final String requestId;
    private final String providerCode;
    private final String providerType;
    private final String providerParam;
    private final String providerMessage;
    private final String sanitizedBody;

    ChatCompletionException(String message, int statusCode, boolean retryable) {
        this(message, statusCode, retryable, null, "", "", "", "", "", null);
    }

    ChatCompletionException(String message, int statusCode, boolean retryable, Throwable cause) {
        this(message, statusCode, retryable, null, "", "", "", "", "", cause);
    }

    ChatCompletionException(String message, int statusCode, boolean retryable, String requestId,
                            String providerCode, String providerType, String providerParam,
                            String providerMessage, String sanitizedBody) {
        this(message, statusCode, retryable, requestId, providerCode, providerType, providerParam,
                providerMessage, sanitizedBody, null);
    }

    private ChatCompletionException(String message, int statusCode, boolean retryable, String requestId,
                                    String providerCode, String providerType, String providerParam,
                                    String providerMessage, String sanitizedBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.requestId = safe(requestId);
        this.providerCode = safe(providerCode);
        this.providerType = safe(providerType);
        this.providerParam = safe(providerParam);
        this.providerMessage = safe(providerMessage);
        this.sanitizedBody = safe(sanitizedBody);
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String requestId() { return requestId; }

    public String providerCode() { return providerCode; }

    public String providerType() { return providerType; }

    public String providerParam() { return providerParam; }

    public String providerMessage() { return providerMessage; }

    public String sanitizedBody() { return sanitizedBody; }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
