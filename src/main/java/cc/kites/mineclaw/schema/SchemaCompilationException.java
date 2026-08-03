package cc.kites.mineclaw.schema;

import java.util.Objects;

/** Stable definition-time failure raised by the strict Schema compiler. */
public final class SchemaCompilationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String path;
    private final String keyword;

    public SchemaCompilationException(String path, String keyword, String message) {
        super(Objects.requireNonNull(path, "path") + ": " + Objects.requireNonNull(message, "message"));
        this.path = path;
        this.keyword = Objects.requireNonNull(keyword, "keyword");
    }

    public String path() {
        return path;
    }

    public String keyword() {
        return keyword;
    }
}
