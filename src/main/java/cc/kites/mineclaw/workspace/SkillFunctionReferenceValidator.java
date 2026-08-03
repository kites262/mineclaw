package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.function.FunctionDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Side-effect-free cross validation of Skill frontmatter against a Function catalog view. */
public final class SkillFunctionReferenceValidator {
    private static final int MAX_FRONTMATTER_CHARS = 64 * 1024;
    private static final Pattern FUNCTION_NAME =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    /**
     * Scans regular Markdown files below {@code skillsDirectory} without following symbolic links.
     * Missing directories are treated as containing no Skill references.
     */
    public Report validate(Path skillsDirectory, FunctionCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        LinkedHashMap<String, Availability> functions = new LinkedHashMap<>();
        for (FunctionDefinition definition : catalog.definitions()) {
            if (validFunctionName(definition.name())) {
                functions.put(definition.name(), switch (definition.status()) {
                    case ENABLED -> Availability.ENABLED;
                    case DISABLED -> Availability.DISABLED;
                    case INVALID -> Availability.INVALID;
                });
            }
        }
        return validate(skillsDirectory, functions);
    }

    /**
     * Scans regular Markdown files below {@code skillsDirectory} without following symbolic links.
     * Missing directories are treated as containing no Skill references.
     */
    public Report validate(Path skillsDirectory, Map<String, Availability> functions) {
        Objects.requireNonNull(skillsDirectory, "skillsDirectory");
        Objects.requireNonNull(functions, "functions");
        functions.forEach((name, availability) -> {
            Objects.requireNonNull(name, "function name");
            Objects.requireNonNull(availability, "function availability");
        });

        Path root = skillsDirectory.toAbsolutePath().normalize();
        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                diagnostics.add(new Diagnostic("skills_not_directory", null, null,
                        "skills path is not a directory"));
            } else {
                scan(root, functions, referenced, diagnostics);
            }
        }

        functions.entrySet().stream()
                .filter(entry -> entry.getValue() == Availability.ENABLED)
                .map(Map.Entry::getKey)
                .sorted()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> diagnostics.add(new Diagnostic(
                        "enabled_function_unreferenced", null, name,
                        "enabled Function has no Skill frontmatter reference")));
        return new Report(referenced, diagnostics);
    }

    private static void scan(
            Path root,
            Map<String, Availability> functions,
            Set<String> referenced,
            List<Diagnostic> diagnostics
    ) {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".md"))
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (IOException | SecurityException exception) {
            diagnostics.add(new Diagnostic("skills_scan_failed", null, null,
                    "cannot scan skills directory"));
            return;
        }
        for (Path file : files) {
            String relative = relative(root, file);
            Frontmatter frontmatter;
            try {
                frontmatter = readFrontmatter(file);
            } catch (IOException | SecurityException exception) {
                diagnostics.add(new Diagnostic("skill_read_failed", relative, null,
                        "cannot read Skill frontmatter"));
                continue;
            } catch (RuntimeException exception) {
                diagnostics.add(new Diagnostic("invalid_skill_frontmatter", relative, null,
                        "Skill frontmatter could not be parsed safely"));
                continue;
            }
            if (frontmatter.errorCode() != null) {
                diagnostics.add(new Diagnostic(frontmatter.errorCode(), relative, null,
                        frontmatter.message()));
                continue;
            }
            Object rawReferences = frontmatter.values().get("functions");
            if (rawReferences == null) {
                continue;
            }
            if (!(rawReferences instanceof List<?> values)) {
                diagnostics.add(new Diagnostic("invalid_skill_functions", relative, null,
                        "Skill frontmatter functions must be a list of Function names"));
                continue;
            }
            LinkedHashSet<String> localReferences = new LinkedHashSet<>();
            for (int index = 0; index < values.size(); index++) {
                Object raw = values.get(index);
                if (!(raw instanceof String name) || !validFunctionName(name)) {
                    diagnostics.add(new Diagnostic("invalid_skill_function_reference", relative, null,
                            "Skill frontmatter functions[" + index + "] is not a valid Function name"));
                    continue;
                }
                if (!localReferences.add(name)) {
                    diagnostics.add(new Diagnostic("duplicate_skill_function_reference",
                            relative, name,
                            "Skill frontmatter references the same Function more than once"));
                    continue;
                }
                referenced.add(name);
                Availability availability = functions.get(name);
                if (availability == null) {
                    diagnostics.add(new Diagnostic("skill_function_missing", relative, name,
                            "Skill references a Function that does not exist"));
                } else if (availability == Availability.DISABLED) {
                    diagnostics.add(new Diagnostic("skill_function_disabled", relative, name,
                            "Skill references a disabled Function"));
                } else if (availability == Availability.INVALID) {
                    diagnostics.add(new Diagnostic("skill_function_invalid", relative, name,
                            "Skill references an invalid Function"));
                }
            }
        }
    }

    private static Frontmatter readFrontmatter(Path file) throws IOException {
        String source;
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             Reader reader = Channels.newReader(channel, decoder, -1)) {
            StringBuilder contents = new StringBuilder();
            char[] buffer = new char[4 * 1024];
            while (contents.length() <= MAX_FRONTMATTER_CHARS) {
                int wanted = Math.min(buffer.length, MAX_FRONTMATTER_CHARS + 1 - contents.length());
                int count = reader.read(buffer, 0, wanted);
                if (count < 0) {
                    break;
                }
                contents.append(buffer, 0, count);
                if (findClosingDelimiter(contents) >= 0) {
                    break;
                }
            }
            source = contents.toString();
        }
        int contentStart = source.startsWith("\uFEFF") ? 1 : 0;
        int firstLineEnd = lineEnd(source, contentStart);
        if (!source.substring(contentStart, firstLineEnd).trim().equals("---")) {
            return Frontmatter.empty();
        }
        int metadataStart = skipLineBreak(source, firstLineEnd);
        int metadataEnd = closingDelimiter(source, metadataStart);
        if (metadataEnd < 0) {
            String code = source.length() > MAX_FRONTMATTER_CHARS
                    ? "skill_frontmatter_too_large" : "skill_frontmatter_unterminated";
            String message = source.length() > MAX_FRONTMATTER_CHARS
                    ? "Skill frontmatter exceeds 65536 characters"
                    : "Skill frontmatter has no closing delimiter";
            return Frontmatter.error(code, message);
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_FRONTMATTER_CHARS);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options))
                    .load(source.substring(metadataStart, metadataEnd));
        } catch (YAMLException exception) {
            return Frontmatter.error("invalid_skill_frontmatter",
                    "Skill frontmatter is not valid safe YAML");
        }
        if (loaded == null) {
            return Frontmatter.empty();
        }
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            return Frontmatter.error("invalid_skill_frontmatter",
                    "Skill frontmatter must be a mapping");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return Frontmatter.error("invalid_skill_frontmatter",
                        "Skill frontmatter keys must be strings");
            }
            values.put(key, entry.getValue());
        }
        return Frontmatter.values(values);
    }

    private static boolean validFunctionName(String value) {
        return !value.isEmpty() && value.length() <= 96 && value.chars().allMatch(character -> character < 128)
                && FUNCTION_NAME.matcher(value).matches();
    }

    private static int findClosingDelimiter(CharSequence source) {
        return closingDelimiter(source.toString(), skipLineBreak(source.toString(),
                lineEnd(source.toString(), 0)));
    }

    private static int closingDelimiter(String source, int start) {
        int cursor = start;
        while (cursor <= source.length()) {
            int end = lineEnd(source, cursor);
            if (source.substring(cursor, end).trim().equals("---")) {
                return cursor;
            }
            if (end == source.length()) {
                return -1;
            }
            cursor = skipLineBreak(source, end);
        }
        return -1;
    }

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

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public enum Availability {
        ENABLED,
        DISABLED,
        INVALID
    }

    public record Diagnostic(String code, String skillPath, String functionName, String message) {
        public Diagnostic {
            code = Objects.requireNonNull(code, "code");
            message = Objects.requireNonNull(message, "message");
        }
    }

    public record Report(Set<String> referencedFunctions, List<Diagnostic> diagnostics) {
        public Report {
            referencedFunctions = Set.copyOf(Objects.requireNonNull(
                    referencedFunctions, "referencedFunctions"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    private record Frontmatter(Map<String, Object> values, String errorCode, String message) {
        private static Frontmatter empty() {
            return values(Map.of());
        }

        private static Frontmatter values(Map<String, Object> values) {
            return new Frontmatter(Collections.unmodifiableMap(new LinkedHashMap<>(values)), null, null);
        }

        private static Frontmatter error(String code, String message) {
            return new Frontmatter(Map.of(), code, message);
        }
    }
}
