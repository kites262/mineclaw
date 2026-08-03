package cc.kites.mineclaw.schema;

import java.util.Objects;

/** One stable, model-safe argument violation using the PRD JSONPath subset. */
public record SchemaViolation(String path, String keyword, String message) {
    public SchemaViolation {
        path = Objects.requireNonNull(path, "path");
        keyword = Objects.requireNonNull(keyword, "keyword");
        message = Objects.requireNonNull(message, "message");
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException("violation path must start with $");
        }
        if (keyword.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("violation keyword and message must not be blank");
        }
    }
}
