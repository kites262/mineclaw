package cc.kites.mineclaw.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
        Api api,
        Context context,
        Chat chat,
        Tools tools,
        Commands commands,
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
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(commands, "commands");
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
                new Api(URI.create("https://api.openai.com/v1/chat/completions"), "MINECLAW_API_KEY",
                        "gpt-5-mini", 60_000, 5, 500),
                new Context(24, 24_000),
                new Chat("@ai", Optional.empty(), 2_000, 120),
                new Tools(true, Set.of()),
                new Commands(false,
                        List.of(Pattern.compile(
                                "^locate structure #?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$")),
                        List.of()),
                new RateLimit(5_000, 1_000),
                new Workspace(true, new Workspace.MaxChars(16_000)),
                new FileTools(12_000, 100, 4, 3_000),
                new Turn(8, 24),
                new Identity("Mineclaw"),
                new Environment(12, 250, new Environment.Inventory(true, 36)),
                new Logging(Level.INFO));
    }

    public record Api(
            URI baseUrl,
            String apiKey,
            String model,
            long timeoutMillis,
            int maxRetries,
            long retryBackoffMillis
    ) {
        public Api {
            Objects.requireNonNull(baseUrl, "baseUrl");
            apiKey = Objects.requireNonNull(apiKey, "apiKey").trim();
            model = Objects.requireNonNull(model, "model").trim();
        }

        /** The loader has already resolved this value into the immutable config snapshot. */
        public Optional<String> configuredApiKey() {
            if (apiKey.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(apiKey);
        }

        @Override
        public String toString() {
            return "Api[baseUrl=protected, apiKey=protected, model=protected"
                    + ", timeoutMillis=" + timeoutMillis + ", maxRetries=" + maxRetries
                    + ", retryBackoffMillis=" + retryBackoffMillis + ']';
        }
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

    public record Context(int maxMessages, int maxTokens) {
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

    public record Commands(
            boolean runEnabled,
            List<Pattern> playerWhitelist,
            List<Pattern> consoleWhitelist
    ) {
        public Commands {
            playerWhitelist = immutablePatterns(playerWhitelist, "playerWhitelist");
            consoleWhitelist = immutablePatterns(consoleWhitelist, "consoleWhitelist");
        }

        public boolean playerCommandAllowed(String normalizedCommand) {
            return matches(playerWhitelist, normalizedCommand);
        }

        public boolean consoleCommandAllowed(String normalizedCommand) {
            return matches(consoleWhitelist, normalizedCommand);
        }

        private static boolean matches(List<Pattern> patterns, String command) {
            Objects.requireNonNull(command, "command");
            return patterns.stream().anyMatch(pattern -> pattern.matcher(command).matches());
        }

        @Override
        public boolean equals(Object other) {
            return other == this || other instanceof Commands commands
                    && runEnabled == commands.runEnabled
                    && patternSources(playerWhitelist).equals(patternSources(commands.playerWhitelist))
                    && patternSources(consoleWhitelist).equals(patternSources(commands.consoleWhitelist));
        }

        @Override
        public int hashCode() {
            return Objects.hash(runEnabled, patternSources(playerWhitelist), patternSources(consoleWhitelist));
        }

        private static List<String> patternSources(List<Pattern> patterns) {
            return patterns.stream().map(Pattern::pattern).toList();
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

    public record Identity(String name) {
        public Identity {
            name = Objects.requireNonNull(name, "name").trim();
        }
    }

    public record Environment(
            int lookDistance,
            long toolCooldownMillis,
            Inventory inventory
    ) {
        public Environment {
            Objects.requireNonNull(inventory, "inventory");
        }

        public record Inventory(boolean includeEquipment, int maxSlots) {
        }
    }

    public record Logging(Level level) {
        public Logging {
            Objects.requireNonNull(level, "level");
        }

        public String configuredName() {
            return level.getName().toUpperCase(Locale.ROOT);
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

    private static List<Pattern> immutablePatterns(List<Pattern> source, String field) {
        Objects.requireNonNull(source, field);
        ArrayList<Pattern> copy = new ArrayList<>(source.size());
        for (Pattern value : source) {
            copy.add(Objects.requireNonNull(value, field + " entry"));
        }
        return List.copyOf(copy);
    }
}
