package cc.kites.mineclaw.schema;

/** Immutable structural budgets shared by Schema compilation and argument validation. */
public record SchemaLimits(
        int maxChars,
        int maxDepth,
        int maxMembers,
        int maxViolations
) {
    public SchemaLimits {
        if (maxChars < 1 || maxDepth < 1 || maxMembers < 1 || maxViolations < 1) {
            throw new IllegalArgumentException("schema limits must be positive");
        }
    }

    public static SchemaLimits defaults() {
        return new SchemaLimits(32_768, 16, 2_048, 8);
    }
}
