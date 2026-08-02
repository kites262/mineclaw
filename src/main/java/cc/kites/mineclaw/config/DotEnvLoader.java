package cc.kites.mineclaw.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Minimal, non-expanding dotenv parser used only for local secret values. */
final class DotEnvLoader {
    static final int MAX_BYTES = 65_536;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DotEnvLoader() {
    }

    static MineclawConfig.SecretEnvironment load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "path");
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return MineclawConfig.SecretEnvironment.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigException(".env must be a regular file and must not be a symbolic link");
        }

        byte[] bytes;
        try (InputStream input = java.nio.channels.Channels.newInputStream(Files.newByteChannel(path,
                java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new ConfigException("Cannot read .env", exception);
        }
        if (bytes.length > MAX_BYTES) {
            throw new ConfigException(".env exceeds the 65536-byte limit");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ConfigException(".env is not valid UTF-8", exception);
        }
        return parse(text);
    }

    static MineclawConfig.SecretEnvironment parse(String text) throws ConfigException {
        Objects.requireNonNull(text, "text");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            parseLine(line, index + 1, values);
        }
        return MineclawConfig.SecretEnvironment.of(values);
    }

    private static void parseLine(String source, int lineNumber, Map<String, String> values)
            throws ConfigException {
        String line = source.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith("export") && line.length() > "export".length()
                && Character.isWhitespace(line.charAt("export".length()))) {
            line = line.substring("export".length()).stripLeading();
        }

        int separator = line.indexOf('=');
        if (separator < 1) {
            throw invalid(lineNumber);
        }
        String name = line.substring(0, separator).trim();
        if (!NAME.matcher(name).matches()) {
            throw invalid(lineNumber);
        }
        String valueSource = line.substring(separator + 1);
        String rawValue = valueSource.strip();
        boolean whitespaceBeforeComment = !valueSource.isEmpty()
                && Character.isWhitespace(valueSource.charAt(0))
                && rawValue.startsWith("#");
        values.put(name, whitespaceBeforeComment ? "" : value(rawValue, lineNumber));
    }

    private static String value(String raw, int lineNumber) throws ConfigException {
        if (raw.isEmpty()) {
            return "";
        }
        char quote = raw.charAt(0);
        if (quote != '\'' && quote != '"') {
            return unquoted(raw);
        }

        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        int index = 1;
        for (; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (quote == '"' && escaped) {
                switch (character) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '\\', '"' -> result.append(character);
                    default -> {
                        result.append('\\');
                        result.append(character);
                    }
                }
                escaped = false;
            } else if (quote == '"' && character == '\\') {
                escaped = true;
            } else if (character == quote) {
                break;
            } else {
                result.append(character);
            }
        }
        if (index == raw.length() || escaped) {
            throw invalid(lineNumber);
        }
        String suffix = raw.substring(index + 1).strip();
        if (!suffix.isEmpty() && !suffix.startsWith("#")) {
            throw invalid(lineNumber);
        }
        return result.toString();
    }

    private static String unquoted(String raw) {
        for (int index = 1; index < raw.length(); index++) {
            if (raw.charAt(index) == '#' && Character.isWhitespace(raw.charAt(index - 1))) {
                return raw.substring(0, index).stripTrailing();
            }
        }
        return raw;
    }

    private static ConfigException invalid(int lineNumber) {
        return new ConfigException(".env contains an invalid entry at line " + lineNumber);
    }
}
