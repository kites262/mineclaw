package cc.kites.mineclaw.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Immutable, validated schema-1 runtime configuration. */
public record MineclawConfig(
        int schema,
        Context context,
        Chat chat,
        Tools tools,
        Functions functions,
        JavaScript javascript,
        RateLimit rateLimit,
        Workspace workspace,
        FileTools fileTools,
        Turn turn,
        Identity identity,
        Environment environment,
        Logging logging
) {
    public static final int SCHEMA = 1;

    public MineclawConfig {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(javascript, "javascript");
        Objects.requireNonNull(rateLimit, "rateLimit");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(fileTools, "fileTools");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(logging, "logging");
    }

    /** Values used when a schema-1 key is omitted from config.yml. */
    public static MineclawConfig defaults() {
        return new MineclawConfig(
                SCHEMA,
                new Context(240),
                new Chat("@ai", Optional.empty(), 2_000, 120),
                new Tools(true, Set.of()),
                new Functions(1_048_576, 256, 512, 32_768, 16, 2_048, 8),
                new JavaScript(65_536, 64, 16, 16, 1_000, 300_000,
                        32_768, 16, 2_048),
                new RateLimit(5_000, 1_000),
                new Workspace(true, new Workspace.MaxChars(16_000)),
                new FileTools(12_000, 100, 4, 3_000),
                new Turn(80, 240),
                new Identity("Mineclaw", true, false),
                new Environment(12, 10, new Environment.ItemInspect(36, 12_000)),
                new Logging(Level.INFO));
    }

    /** In-memory dotenv values whose string form deliberately never exposes secret contents. */
    public static final class SecretEnvironment {
        private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
        private static final SecretEnvironment EMPTY = new SecretEnvironment(Map.of());
        private final Map<String, String> values;

        private SecretEnvironment(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        static SecretEnvironment empty() {
            return EMPTY;
        }

        static SecretEnvironment of(Map<String, String> values) {
            Objects.requireNonNull(values, "values");
            return values.isEmpty() ? EMPTY : new SecretEnvironment(values);
        }

        static boolean validName(String value) {
            return NAME.matcher(value).matches();
        }

        String get(String name) {
            return values.get(name);
        }

        @Override
        public boolean equals(Object other) {
            return other == this || other instanceof SecretEnvironment environment
                    && values.equals(environment.values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public String toString() {
            return "protected";
        }
    }

    public record Context(int maxMessages) {
    }

    public record Chat(
            String publicPrefix,
            Optional<Pattern> wakePattern,
            int replyMaxChars,
            int actionbarMaxChars
    ) {
        public Chat {
            publicPrefix = Objects.requireNonNull(publicPrefix, "publicPrefix");
            wakePattern = Objects.requireNonNull(wakePattern, "wakePattern");
        }

        public Optional<String> wakePatternSource() {
            return wakePattern.map(Pattern::pattern);
        }

        @Override
        public boolean equals(Object other) {
            return other == this || other instanceof Chat chat
                    && publicPrefix.equals(chat.publicPrefix)
                    && wakePatternSource().equals(chat.wakePatternSource())
                    && replyMaxChars == chat.replyMaxChars
                    && actionbarMaxChars == chat.actionbarMaxChars;
        }

        @Override
        public int hashCode() {
            return Objects.hash(publicPrefix, wakePatternSource(), replyMaxChars, actionbarMaxChars);
        }
    }

    public record Tools(boolean enabled, Set<String> disabled) {
        public Tools {
            disabled = immutableStrings(disabled, "disabled");
        }

        public boolean isDisabled(String toolName) {
            return disabled.contains(Objects.requireNonNull(toolName, "toolName"));
        }
    }

    /** Loading and argument-validation limits for the protected Function registry. */
    public record Functions(
            int maxFileChars,
            int maxEntries,
            int maxDescriptionChars,
            int maxArgumentChars,
            int maxArgumentDepth,
            int maxArgumentMembers,
            int maxValidationViolations
    ) {
        public Functions {
            if (maxFileChars < 1 || maxEntries < 1 || maxDescriptionChars < 1
                    || maxArgumentChars < 1 || maxArgumentDepth < 1
                    || maxArgumentMembers < 1 || maxValidationViolations < 1) {
                throw new IllegalArgumentException("function limits must be positive");
            }
        }
    }

    /** Resource and serialization limits for one declarative JavaScript workflow. */
    public record JavaScript(
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
        public JavaScript {
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
    }

    public record RateLimit(long playerCooldownMillis, long globalCooldownMillis) {
    }

    public record Workspace(boolean seedDefaults, MaxChars maxChars) {
        public Workspace {
            Objects.requireNonNull(maxChars, "maxChars");
        }

        public record MaxChars(int agents) {
        }
    }

    public record FileTools(int maxReadChars, int maxResults, int maxDepth, long timeoutMillis) {
    }

    public record Turn(int maxToolRounds, int maxToolCalls) {
    }

    public record Identity(String name, boolean includePlayerNameField,
                           boolean includePlayerContentPrefix) {
        public Identity {
            name = Objects.requireNonNull(name, "name").trim();
        }
    }

    public record Environment(
            int lookDistance,
            long toolCooldownMillis,
            ItemInspect itemInspect
    ) {
        public Environment {
            Objects.requireNonNull(itemInspect, "itemInspect");
        }

        public record ItemInspect(int maxSlots, int maxOutputChars) {
        }
    }

    public record Logging(Level level) {
        public Logging {
            Objects.requireNonNull(level, "level");
        }

        public String configuredName() {
            return level.getName().toUpperCase(Locale.ROOT);
        }

        public boolean requestDiagnosticsEnabled() {
            return Level.ALL.equals(level);
        }
    }

    private static Set<String> immutableStrings(Set<String> source, String field) {
        Objects.requireNonNull(source, field);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : source) {
            copy.add(Objects.requireNonNull(value, field + " entry"));
        }
        return Collections.unmodifiableSet(copy);
    }

}
