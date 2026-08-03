package cc.kites.mineclaw.javascript;

/** Immutable resource and serialization limits for declarative JavaScript workflows. */
public record JavaScriptLimits(
        int maxSourceChars,
        int maxOperationsPerInvocation,
        int maxConcurrentOperations,
        int maxPendingApprovals,
        long maxSyncSegmentMillis,
        long maxWorkflowMillis,
        int maxResultChars,
        int maxResultDepth,
        int maxResultMembers
) {
    public JavaScriptLimits {
        if (maxSourceChars < 1 || maxOperationsPerInvocation < 1
                || maxConcurrentOperations < 1 || maxPendingApprovals < 1
                || maxSyncSegmentMillis < 1L || maxWorkflowMillis < 1L
                || maxResultChars < 1 || maxResultDepth < 1 || maxResultMembers < 1) {
            throw new IllegalArgumentException("javascript limits must be positive");
        }
        if (maxConcurrentOperations > maxOperationsPerInvocation) {
            throw new IllegalArgumentException(
                    "maxConcurrentOperations must not exceed maxOperationsPerInvocation");
        }
        if (maxPendingApprovals > maxConcurrentOperations) {
            throw new IllegalArgumentException(
                    "maxPendingApprovals must not exceed maxConcurrentOperations");
        }
    }

    public static JavaScriptLimits defaults() {
        return new JavaScriptLimits(65_536, 64, 16, 16, 1_000L, 300_000L,
                32_768, 16, 2_048);
    }
}
