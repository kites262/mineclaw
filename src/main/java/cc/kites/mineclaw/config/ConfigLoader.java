package cc.kites.mineclaw.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strict schema-1 config.yml parser backed by Bukkit's {@link YamlConfiguration}. */
public final class ConfigLoader {
    public MineclawConfig load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new ConfigException("config.yml must have a parent directory");
        }
        final String source;
        try {
            source = new cc.kites.mineclaw.workspace.WorkspacePathSecurity(parent)
                    .readFixedUtf8(absolute, "config.yml");
        } catch (IOException exception) {
            throw new ConfigException("Cannot safely read config.yml", exception);
        }
        return parse(source);
    }

    public MineclawConfig parse(String yamlText) throws ConfigException {
        Objects.requireNonNull(yamlText, "yamlText");
        // Validate through the same JSON-safe, duplicate-key/alias/tag rejecting boundary as
        // providers.yml and whitelist.yml before Bukkit maps scalar values into sections.
        StrictYaml.parse(yamlText, "config.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(yamlText);
        } catch (InvalidConfigurationException exception) {
            // Do not retain a cause whose message may include the secret-bearing source line.
            throw new ConfigException("config.yml contains invalid YAML");
        }
        return parse(yaml);
    }

    public MineclawConfig parse(ConfigurationSection yaml) throws ConfigException {
        Objects.requireNonNull(yaml, "yaml");
        validateSectionShapes(yaml);
        validateKnownFields(yaml);
        if (yaml.contains("api")) {
            throw invalid("api", "is a legacy section; configure providers.yml");
        }
        if (yaml.contains("commands")) {
            throw invalid("commands", "is a legacy section; configure whitelist.yml");
        }
        MineclawConfig defaults = MineclawConfig.defaults();

        int schema = integer(yaml, "schema", defaults.schema());
        if (schema != MineclawConfig.SCHEMA) {
            throw invalid("schema", "must be " + MineclawConfig.SCHEMA);
        }
        MineclawConfig.Context contextDefaults = defaults.context();
        MineclawConfig.Context context = new MineclawConfig.Context(
                positiveInt(yaml, "context.max_messages", contextDefaults.maxMessages()));

        MineclawConfig.Chat chatDefaults = defaults.chat();
        String publicPrefix = string(yaml, "chat.public_prefix", chatDefaults.publicPrefix());
        String rawWakePattern = string(yaml, "chat.wake_pattern", "").trim();
        Optional<Pattern> wakePattern = rawWakePattern.isEmpty()
                ? Optional.empty()
                : Optional.of(pattern(rawWakePattern, "chat.wake_pattern"));
        if (publicPrefix.isBlank() && wakePattern.isEmpty()) {
            throw invalid("chat", "public_prefix and wake_pattern cannot both be empty");
        }
        MineclawConfig.Chat chat = new MineclawConfig.Chat(
                publicPrefix,
                wakePattern,
                positiveInt(yaml, "chat.reply_max_chars", chatDefaults.replyMaxChars()),
                positiveInt(yaml, "chat.actionbar_max_chars", chatDefaults.actionbarMaxChars()));

        MineclawConfig.Tools toolsDefaults = defaults.tools();
        MineclawConfig.Tools tools = new MineclawConfig.Tools(
                bool(yaml, "tools.enabled", toolsDefaults.enabled()),
                normalizedNames(strings(yaml, "tools.disabled", List.copyOf(toolsDefaults.disabled())),
                        "tools.disabled"));

        MineclawConfig.Functions functionDefaults = defaults.functions();
        MineclawConfig.Functions functions = new MineclawConfig.Functions(
                boundedPositiveInt(yaml, "functions.max_file_chars",
                        functionDefaults.maxFileChars(), 16_000_000),
                boundedPositiveInt(yaml, "functions.max_entries",
                        functionDefaults.maxEntries(), 10_000),
                boundedPositiveInt(yaml, "functions.max_description_chars",
                        functionDefaults.maxDescriptionChars(), 512),
                boundedPositiveInt(yaml, "functions.max_argument_chars",
                        functionDefaults.maxArgumentChars(), 1_000_000),
                boundedPositiveInt(yaml, "functions.max_argument_depth",
                        functionDefaults.maxArgumentDepth(), 64),
                boundedPositiveInt(yaml, "functions.max_argument_members",
                        functionDefaults.maxArgumentMembers(), 100_000),
                boundedPositiveInt(yaml, "functions.max_validation_violations",
                        functionDefaults.maxValidationViolations(), 1_024));

        MineclawConfig.JavaScript javascriptDefaults = defaults.javascript();
        int maxOperations = boundedPositiveInt(yaml, "javascript.max_operations_per_invocation",
                javascriptDefaults.maxOperationsPerInvocation(), 10_000);
        int maxConcurrent = boundedPositiveInt(yaml, "javascript.max_concurrent_operations",
                javascriptDefaults.maxConcurrentOperations(), 1_024);
        if (maxConcurrent > maxOperations) {
            throw invalid("javascript.max_concurrent_operations",
                    "must not exceed javascript.max_operations_per_invocation");
        }
        int maxApprovals = boundedPositiveInt(yaml, "javascript.max_pending_approvals",
                javascriptDefaults.maxPendingApprovals(), 1_024);
        if (maxApprovals > maxConcurrent) {
            throw invalid("javascript.max_pending_approvals",
                    "must not exceed javascript.max_concurrent_operations");
        }
        MineclawConfig.JavaScript javascript = new MineclawConfig.JavaScript(
                boundedPositiveInt(yaml, "javascript.max_source_chars",
                        javascriptDefaults.maxSourceChars(), 1_000_000),
                maxOperations,
                maxConcurrent,
                maxApprovals,
                boundedPositiveLong(yaml, "javascript.max_sync_segment_ms",
                        javascriptDefaults.maxSyncSegmentMillis(), 60_000L),
                boundedPositiveLong(yaml, "javascript.max_workflow_ms",
                        javascriptDefaults.maxWorkflowMillis(), 86_400_000L),
                boundedPositiveInt(yaml, "javascript.max_result_chars",
                        javascriptDefaults.maxResultChars(), 1_000_000),
                boundedPositiveInt(yaml, "javascript.max_result_depth",
                        javascriptDefaults.maxResultDepth(), 64),
                boundedPositiveInt(yaml, "javascript.max_result_members",
                        javascriptDefaults.maxResultMembers(), 100_000));

        MineclawConfig.RateLimit rateDefaults = defaults.rateLimit();
        MineclawConfig.RateLimit rateLimit = new MineclawConfig.RateLimit(
                nonNegativeLong(yaml, "rate_limit.player_cooldown_ms", rateDefaults.playerCooldownMillis()),
                nonNegativeLong(yaml, "rate_limit.global_cooldown_ms", rateDefaults.globalCooldownMillis()));

        MineclawConfig.Workspace workspaceDefaults = defaults.workspace();
        MineclawConfig.Workspace workspace = new MineclawConfig.Workspace(
                bool(yaml, "workspace.seed_defaults", workspaceDefaults.seedDefaults()),
                new MineclawConfig.Workspace.MaxChars(boundedPositiveInt(
                        yaml, "workspace.max_chars.agents", workspaceDefaults.maxChars().agents(), 1_000_000)));

        MineclawConfig.FileTools fileDefaults = defaults.fileTools();
        MineclawConfig.FileTools fileTools = new MineclawConfig.FileTools(
                positiveInt(yaml, "file_tools.max_read_chars", fileDefaults.maxReadChars()),
                positiveInt(yaml, "file_tools.max_results", fileDefaults.maxResults()),
                nonNegativeInt(yaml, "file_tools.max_depth", fileDefaults.maxDepth()),
                positiveLong(yaml, "file_tools.timeout", fileDefaults.timeoutMillis()));

        MineclawConfig.Turn turnDefaults = defaults.turn();
        MineclawConfig.Turn turn = new MineclawConfig.Turn(
                positiveInt(yaml, "turn.max_tool_rounds", turnDefaults.maxToolRounds()),
                positiveInt(yaml, "turn.max_tool_calls", turnDefaults.maxToolCalls()));

        MineclawConfig.Identity identity = new MineclawConfig.Identity(
                string(yaml, "identity.name", defaults.identity().name()));

        MineclawConfig.Environment environmentDefaults = defaults.environment();
        MineclawConfig.Environment environment = new MineclawConfig.Environment(
                positiveInt(yaml, "environment.look_distance", environmentDefaults.lookDistance()),
                nonNegativeLong(yaml, "environment.tool_cooldown_ms", environmentDefaults.toolCooldownMillis()),
                new MineclawConfig.Environment.Inventory(
                        bool(yaml, "environment.inventory.include_equipment",
                                environmentDefaults.inventory().includeEquipment()),
                        positiveInt(yaml, "environment.inventory.max_slots",
                                environmentDefaults.inventory().maxSlots())));

        String rawLevel = nonBlank(string(yaml, "logging.level", defaults.logging().configuredName()),
                "logging.level");
        Level level;
        try {
            level = Level.parse(rawLevel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("logging.level", "unknown java.util.logging level " + rawLevel, exception);
        }

        return new MineclawConfig(schema, context, chat, tools, functions, javascript, rateLimit, workspace,
                fileTools, turn, identity, environment, new MineclawConfig.Logging(level));
    }

    private static void validateSectionShapes(ConfigurationSection yaml) throws ConfigException {
        for (String path : List.of("context", "chat", "tools", "functions", "javascript", "rate_limit",
                "workspace", "workspace.max_chars", "file_tools", "turn", "identity", "environment",
                "environment.inventory", "logging")) {
            Object value = yaml.get(path);
            if (value != null && !(value instanceof ConfigurationSection)) {
                throw invalid(path, "must be a mapping");
            }
        }
    }

    private static void validateKnownFields(ConfigurationSection yaml) throws ConfigException {
        exactFields(yaml, "", Set.of("schema", "api", "commands", "context", "chat", "tools",
                "functions", "javascript", "rate_limit", "workspace", "file_tools", "turn",
                "identity", "environment", "logging"));
        exactFields(yaml, "context", Set.of("max_messages"));
        exactFields(yaml, "chat", Set.of("public_prefix", "wake_pattern", "reply_max_chars",
                "actionbar_max_chars"));
        exactFields(yaml, "tools", Set.of("enabled", "disabled"));
        exactFields(yaml, "functions", Set.of("max_file_chars", "max_entries", "max_description_chars",
                "max_argument_chars", "max_argument_depth", "max_argument_members",
                "max_validation_violations"));
        exactFields(yaml, "javascript", Set.of("max_source_chars", "max_operations_per_invocation",
                "max_concurrent_operations", "max_pending_approvals", "max_sync_segment_ms",
                "max_workflow_ms", "max_result_chars", "max_result_depth", "max_result_members"));
        exactFields(yaml, "rate_limit", Set.of("player_cooldown_ms", "global_cooldown_ms"));
        exactFields(yaml, "workspace", Set.of("seed_defaults", "max_chars"));
        exactFields(yaml, "workspace.max_chars", Set.of("agents"));
        exactFields(yaml, "file_tools", Set.of("max_read_chars", "max_results", "max_depth", "timeout"));
        exactFields(yaml, "turn", Set.of("max_tool_rounds", "max_tool_calls"));
        exactFields(yaml, "identity", Set.of("name"));
        exactFields(yaml, "environment", Set.of("look_distance", "tool_cooldown_ms", "inventory"));
        exactFields(yaml, "environment.inventory", Set.of("include_equipment", "max_slots"));
        exactFields(yaml, "logging", Set.of("level"));
    }

    private static void exactFields(ConfigurationSection root, String path, Set<String> allowed)
            throws ConfigException {
        ConfigurationSection section = path.isEmpty() ? root : root.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String field : section.getKeys(false)) {
            if (!allowed.contains(field)) {
                throw invalid(path.isEmpty() ? field : path + '.' + field, "is not a supported field");
            }
        }
    }

    private static Set<String> normalizedNames(List<String> values, String path) throws ConfigException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index).trim();
            if (value.isEmpty()) {
                throw invalid(path + '[' + index + ']', "must not be blank");
            }
            result.add(value);
        }
        return result;
    }

    private static Pattern pattern(String value, String path) throws ConfigException {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException exception) {
            throw invalid(path, "invalid regular expression: " + exception.getDescription(), exception);
        }
    }

    private static String nonBlank(String value, String path) throws ConfigException {
        String result = value.trim();
        if (result.isEmpty()) {
            throw invalid(path, "must not be blank");
        }
        return result;
    }

    private static boolean bool(ConfigurationSection yaml, String path, boolean defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalid(path, "must be a boolean");
    }

    private static String string(ConfigurationSection yaml, String path, String defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String string) {
            return string;
        }
        throw invalid(path, "must be a string");
    }

    private static List<String> strings(ConfigurationSection yaml, String path, List<String> defaultValue)
            throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return List.copyOf(defaultValue);
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(path, "must be a list of strings");
        }
        ArrayList<String> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object entry = list.get(index);
            if (!(entry instanceof String string)) {
                throw invalid(path + '[' + index + ']', "must be a string");
            }
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static int integer(ConfigurationSection yaml, String path, int defaultValue) throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(path, "is outside the integer range");
        }
        return (int) value;
    }

    private static int positiveInt(ConfigurationSection yaml, String path, int defaultValue) throws ConfigException {
        int value = integer(yaml, path, defaultValue);
        if (value <= 0) {
            throw invalid(path, "must be greater than zero");
        }
        return value;
    }

    private static int nonNegativeInt(ConfigurationSection yaml, String path, int defaultValue)
            throws ConfigException {
        int value = integer(yaml, path, defaultValue);
        if (value < 0) {
            throw invalid(path, "must not be negative");
        }
        return value;
    }

    private static int boundedPositiveInt(
            ConfigurationSection yaml, String path, int defaultValue, int maximum) throws ConfigException {
        int value = positiveInt(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static int boundedNonNegativeInt(
            ConfigurationSection yaml, String path, int defaultValue, int maximum) throws ConfigException {
        int value = nonNegativeInt(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static long positiveLong(ConfigurationSection yaml, String path, long defaultValue)
            throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value <= 0) {
            throw invalid(path, "must be greater than zero");
        }
        return value;
    }

    private static long nonNegativeLong(ConfigurationSection yaml, String path, long defaultValue)
            throws ConfigException {
        long value = integral(yaml, path, defaultValue);
        if (value < 0) {
            throw invalid(path, "must not be negative");
        }
        return value;
    }

    private static long boundedPositiveLong(
            ConfigurationSection yaml, String path, long defaultValue, long maximum) throws ConfigException {
        long value = positiveLong(yaml, path, defaultValue);
        if (value > maximum) {
            throw invalid(path, "must not exceed " + maximum);
        }
        return value;
    }

    private static long integral(ConfigurationSection yaml, String path, long defaultValue) throws ConfigException {
        Object value = yaml.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalid(path, "must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(path, "must be an integer in the 64-bit range", exception);
        }
    }

    private static ConfigException invalid(String path, String message) {
        return new ConfigException(path + ' ' + message);
    }

    private static ConfigException invalid(String path, String message, Throwable cause) {
        return new ConfigException(path + ' ' + message, cause);
    }
}
