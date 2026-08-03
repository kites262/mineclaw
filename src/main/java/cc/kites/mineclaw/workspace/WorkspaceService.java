package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.config.MineclawConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct-from-disk AGENTS.md loading, optional seeding, truncation and display-name discovery. */
public final class WorkspaceService {
    public static final String AGENTS_FILE_NAME = "AGENTS.md";
    public static final String DEFAULT_DISPLAY_NAME = "Mineclaw";
    public static final String DEFAULT_AGENT_TEMPLATE = loadBundledAgentTemplate();

    private static String loadBundledAgentTemplate() {
        try (var input = WorkspaceService.class.getResourceAsStream("/workspace/AGENTS.md")) {
            if (input == null) {
                throw new IOException("Bundled workspace/AGENTS.md is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final int DISPLAY_SCAN_CHARS = 64 * 1024;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 64;

    private final Path root;
    private final Path agentsFile;
    private final WorkspacePathSecurity pathSecurity;
    private final String seedTemplate;
    private final Consumer<String> warningSink;

    public WorkspaceService(Path root, Logger logger) {
        this(root, DEFAULT_AGENT_TEMPLATE, Objects.requireNonNull(logger, "logger")::warning);
    }

    public WorkspaceService(Path root, String seedTemplate, Logger logger) {
        this(root, seedTemplate, Objects.requireNonNull(logger, "logger")::warning);
    }

    public WorkspaceService(Path root, String seedTemplate, Consumer<String> warningSink) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.agentsFile = this.root.resolve(AGENTS_FILE_NAME);
        this.pathSecurity = new WorkspacePathSecurity(this.root);
        this.seedTemplate = Objects.requireNonNull(seedTemplate, "seedTemplate");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    public AgentDocument readAgentDocument(MineclawConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        return readAgentDocument(config.workspace(), config.identity().name());
    }

    public AgentDocument readAgentDocument(MineclawConfig.Workspace settings, String identityName)
            throws IOException {
        Objects.requireNonNull(settings, "settings");
        return readAgentDocument(settings.seedDefaults(), settings.maxChars().agents(), identityName);
    }

    /** Every invocation reads the current file; no AGENTS.md content is cached. */
    public AgentDocument readAgentDocument(boolean seedDefaults, int maxChars, String identityName)
            throws IOException {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than zero");
        }

        boolean seeded = false;
        if (Files.notExists(agentsFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!seedDefaults) {
                return new AgentDocument("", fallbackName(identityName), false, false, 0);
            }
            Files.createDirectories(root);
            pathSecurity.requireFixedSeedTarget(agentsFile, AGENTS_FILE_NAME);
            try {
                Files.writeString(agentsFile, seedTemplate, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                seeded = true;
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent request seeded it first. Read that complete file below.
            }
        } else {
            pathSecurity.requireFixedSeedTarget(agentsFile, AGENTS_FILE_NAME);
        }

        SourcePrefix snapshot = readSourcePrefix(maxChars);
        String source = snapshot.text();
        String displayName = discoverDisplayName(source, identityName);
        boolean truncated = snapshot.observedLength() > maxChars;
        String injected = truncated ? source.substring(0, maxChars) : source;
        if (truncated) {
            warningSink.accept(AGENTS_FILE_NAME + " exceeds workspace.max_chars.agents ("
                    + (snapshot.capped() ? "at least " : "") + snapshot.observedLength()
                    + " > " + maxChars + "); truncating for this request");
        }
        return new AgentDocument(injected, displayName, seeded, truncated, snapshot.observedLength());
    }

    public Path root() {
        return root;
    }

    public Path agentsFile() {
        return agentsFile;
    }

    static String discoverDisplayName(String source, String identityName) {
        String fromFrontMatter = cleanDisplayName(frontMatterName(source));
        if (!fromFrontMatter.isEmpty()) {
            return fromFrontMatter;
        }

        Matcher heading = FIRST_HEADING.matcher(source);
        if (heading.find()) {
            String value = cleanDisplayName(
                    heading.group(1).replaceFirst("\\s+#+\\s*$", ""));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallbackName(identityName);
    }

    private static String frontMatterName(String source) {
        int contentStart = source.startsWith("\uFEFF") ? 1 : 0;
        int firstLineEnd = lineEnd(source, contentStart);
        if (!source.substring(contentStart, firstLineEnd).trim().equals("---")) {
            return null;
        }

        int frontMatterStart = skipLineBreak(source, firstLineEnd);
        int cursor = frontMatterStart;
        int frontMatterEnd = -1;
        while (cursor <= source.length()) {
            int end = lineEnd(source, cursor);
            if (source.substring(cursor, end).trim().equals("---")) {
                frontMatterEnd = cursor;
                break;
            }
            if (end == source.length()) {
                break;
            }
            cursor = skipLineBreak(source, end);
        }
        if (frontMatterEnd < 0) {
            return null;
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(Math.max(1_024, frontMatterEnd - frontMatterStart + 1));
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(source.substring(frontMatterStart, frontMatterEnd));
        } catch (YAMLException exception) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> metadata)) {
            return null;
        }
        String name = scalar(metadata.get("name"));
        if (name != null) {
            return name;
        }
        return scalar(metadata.get("display_name"));
    }

    private static String scalar(Object value) {
        if (!(value instanceof String string)) {
            return null;
        }
        String trimmed = string.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String fallbackName(String identityName) {
        String cleaned = cleanDisplayName(identityName);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        return DEFAULT_DISPLAY_NAME;
    }

    private SourcePrefix readSourcePrefix(int maxChars) throws IOException {
        int retainedLimit = (int) Math.min(Integer.MAX_VALUE,
                Math.max((long) DISPLAY_SCAN_CHARS, (long) maxChars + 1L));
        StringBuilder retained = new StringBuilder(Math.min(retainedLimit, 16 * 1024));
        boolean capped = false;
        try (Reader reader = pathSecurity.openFixedUtf8(agentsFile, AGENTS_FILE_NAME)) {
            char[] buffer = new char[4 * 1024];
            while (retained.length() < retainedLimit) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, retainedLimit - retained.length()));
                if (count < 0) {
                    break;
                }
                retained.append(buffer, 0, count);
            }
            if (retained.length() == retainedLimit && reader.read() >= 0) {
                capped = true;
            }
        }
        int observedLength = retained.length() + (capped ? 1 : 0);
        return new SourcePrefix(retained.toString(), observedLength, capped);
    }

    private static String cleanDisplayName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        int codePoints = 0;
        for (int offset = 0; offset < value.length() && codePoints < MAX_DISPLAY_NAME_CODE_POINTS;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                codePoints++;
                pendingSpace = false;
                if (codePoints >= MAX_DISPLAY_NAME_CODE_POINTS) {
                    break;
                }
            }
            result.appendCodePoint(codePoint);
            codePoints++;
        }
        return result.toString();
    }

    private record SourcePrefix(String text, int observedLength, boolean capped) { }

    private static int lineEnd(String source, int start) {
        int newline = source.indexOf('\n', start);
        if (newline < 0) {
            return source.length();
        }
        int end = newline;
        if (end > start && source.charAt(end - 1) == '\r') {
            end--;
        }
        return end;
    }

    private static int skipLineBreak(String source, int lineEnd) {
        int cursor = lineEnd;
        if (cursor < source.length() && source.charAt(cursor) == '\r') {
            cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\n') {
            cursor++;
        }
        return cursor;
    }
}
