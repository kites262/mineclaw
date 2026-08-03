package cc.kites.mineclaw.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Sandboxed, read-only, hot-reading implementation of list/read/grep. */
public final class WorkspaceFileTools {
    private static final long MAX_GREP_FILE_BYTES = 4L * 1024L * 1024L;

    private final Path root;

    public WorkspaceFileTools(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toAbsolutePath().normalize();
    }

    public ToolResult list(JsonObject arguments, Limits limits) {
        JsonObject envelope = envelope();
        try {
            long deadline = deadline(limits.timeoutMillis());
            Path directory = resolve(text(arguments, "path", ""), true);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return error(envelope, "not_directory", "`path` 不是目录");
            }
            int depth = boundedInt(arguments, "depth", 1, 0, limits.maxDepth());
            int limit = boundedInt(arguments, "limit", limits.maxResults(), 1, limits.maxResults());
            JsonArray items = envelope.getAsJsonArray("items");
            boolean truncated = false;
            try (Stream<Path> stream = Files.walk(directory, depth)) {
                ArrayList<Path> paths = new ArrayList<>();
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    checkDeadline(deadline);
                    Path path = iterator.next();
                    if (!path.equals(directory)) {
                        paths.add(path);
                        if (paths.size() > limit) {
                            truncated = true;
                            break;
                        }
                    }
                }
                paths.sort(Comparator.comparing(this::relativeText));
                for (Path path : paths) {
                    checkDeadline(deadline);
                    String relative = relativeText(path);
                    if (items.size() >= limit) {
                        break;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("path", relative);
                    item.addProperty("type", type(path));
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        item.addProperty("size", Files.size(path));
                    }
                    items.add(item);
                }
            }
            envelope.addProperty("truncated", truncated);
            return new ToolResult("ok", envelope);
        } catch (ToolFailure failure) {
            return error(envelope, failure.code, failure.getMessage());
        } catch (IOException | SecurityException exception) {
            return error(envelope, "io_error", safeMessage(exception));
        }
    }

    public ToolResult read(JsonObject arguments, Limits limits) {
        JsonObject envelope = envelope();
        if (!arguments.has("path") || !arguments.get("path").isJsonPrimitive()) {
            return invalid(envelope, "missing_path", "`path` 是必填字符串");
        }
        try {
            long deadline = deadline(limits.timeoutMillis());
            String requestedPath = arguments.get("path").getAsString();
            Path file = resolve(requestedPath, true);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return error(envelope, "not_file", "`path` 不是普通文件");
            }
            int offset = boundedInt(arguments, "offset", 0, 0, Integer.MAX_VALUE);
            int maximum = boundedInt(arguments, "max_chars", limits.maxReadChars(), 1,
                    limits.maxReadChars());
            StringBuilder content = new StringBuilder(maximum);
            boolean truncated;
            try (Reader reader = openUtf8NoFollow(file)) {
                long remaining = offset;
                while (remaining > 0L) {
                    checkDeadline(deadline);
                    long skipped = reader.skip(remaining);
                    if (skipped == 0L) {
                        if (reader.read() < 0) {
                            break;
                        }
                        skipped = 1L;
                    }
                    remaining -= skipped;
                }
                char[] buffer = new char[(int) Math.min(2048L, (long) maximum + 1L)];
                while (content.length() <= maximum) {
                    checkDeadline(deadline);
                    int wanted = Math.min(buffer.length, maximum + 1 - content.length());
                    int count = reader.read(buffer, 0, wanted);
                    if (count < 0) {
                        break;
                    }
                    content.append(buffer, 0, count);
                }
                truncated = content.length() > maximum;
            }
            if (truncated) {
                content.setLength(maximum);
            }
            envelope.addProperty("content", content.toString());
            envelope.addProperty("truncated", truncated);
            return new ToolResult("ok", envelope);
        } catch (ToolFailure failure) {
            return error(envelope, failure.code, failure.getMessage());
        } catch (IOException | SecurityException exception) {
            return error(envelope, "io_error", safeMessage(exception));
        }
    }

    public ToolResult grep(JsonObject arguments, Limits limits) {
        JsonObject envelope = envelope();
        if (!arguments.has("pattern") || !arguments.get("pattern").isJsonPrimitive()
                || arguments.get("pattern").getAsString().isEmpty()) {
            return invalid(envelope, "missing_pattern", "`pattern` 是必填的非空字符串");
        }
        String pattern = arguments.get("pattern").getAsString();
        if (pattern.length() > 1_024) {
            return invalid(envelope, "pattern_too_long", "`pattern` 最多允许 1024 个字符");
        }
        try {
            long deadline = deadline(limits.timeoutMillis());
            String requestedPath = text(arguments, "path", "");
            int maximum = boundedInt(arguments, "max_results", limits.maxResults(), 1, limits.maxResults());
            int contextLines = boundedInt(arguments, "context_lines", 0, 0, 10);
            JsonArray matches = envelope.getAsJsonArray("matches");
            boolean truncated = false;
            Path target = resolve(requestedPath, true);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                truncated = scanFile(target, pattern, maximum, contextLines, matches, deadline);
            } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> stream = Files.walk(target, limits.maxDepth())) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        checkDeadline(deadline);
                        Path file = iterator.next();
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                && scanFile(file, pattern, maximum, contextLines, matches, deadline)) {
                            truncated = true;
                            break;
                        }
                    }
                }
            } else {
                return error(envelope, "not_found", "`path` 不存在，或不是普通文件或目录");
            }
            envelope.addProperty("truncated", truncated);
            return new ToolResult("ok", envelope);
        } catch (ToolFailure failure) {
            return error(envelope, failure.code, failure.getMessage());
        } catch (IOException | SecurityException exception) {
            return error(envelope, "io_error", safeMessage(exception));
        }
    }

    private boolean scanFile(Path file, String pattern, int maximum, int contextLines,
                             JsonArray matches, long deadline) throws IOException, ToolFailure {
        if (Files.size(file) > MAX_GREP_FILE_BYTES) {
            throw new ToolFailure("file_too_large", "grep 的单文件上限为 4 MiB：" + relativeText(file));
        }
        Deque<String> before = new ArrayDeque<>();
        List<PendingContext> pending = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(openUtf8NoFollow(file))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                checkDeadline(deadline);
                for (int index = pending.size() - 1; index >= 0; index--) {
                    PendingContext context = pending.get(index);
                    context.after.add(shorten(line, 1024));
                    if (--context.remaining == 0) {
                        pending.remove(index);
                    }
                }
                if (matches.size() < maximum && line.contains(pattern)) {
                    JsonObject match = new JsonObject();
                    match.addProperty("path", relativeText(file));
                    match.addProperty("line", number);
                    match.addProperty("text", shorten(line, 1024));
                    JsonArray previous = new JsonArray();
                    before.forEach(previous::add);
                    JsonArray after = new JsonArray();
                    match.add("context_before", previous);
                    match.add("context_after", after);
                    matches.add(match);
                    if (contextLines > 0) {
                        pending.add(new PendingContext(after, contextLines));
                    }
                }
                before.addLast(shorten(line, 1024));
                while (before.size() > contextLines) {
                    before.removeFirst();
                }
                if (matches.size() >= maximum && pending.isEmpty()) {
                    return true;
                }
            }
        }
        return matches.size() >= maximum;
    }

    private Path resolve(String text, boolean existing) throws ToolFailure, IOException {
        final Path relative;
        try {
            relative = text.isBlank() ? Path.of("") : Path.of(text);
        } catch (InvalidPathException exception) {
            throw new ToolFailure("invalid_path", "`path` 格式无效");
        }
        if (relative.isAbsolute()) {
            throw new ToolFailure("path_escape", "只允许使用 Workspace 相对路径");
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new ToolFailure("path_escape", "`path` 超出 Workspace 根目录");
        }
        if (existing && Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolFailure("not_found", "`path` 不存在");
        }
        Path realRoot = root.toRealPath();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot)) {
            throw new ToolFailure("path_escape", "符号链接超出 Workspace 根目录");
        }
        return candidate;
    }

    /** The final path component must still be a regular file when it is actually opened. */
    private static Reader openUtf8NoFollow(Path file) throws IOException {
        return new InputStreamReader(Channels.newInputStream(Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))), StandardCharsets.UTF_8);
    }

    private String relativeText(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String type(Path path) {
        if (Files.isSymbolicLink(path)) {
            return "symlink";
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return "directory";
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "file";
        }
        return "other";
    }

    private static String text(JsonObject arguments, String name, String fallback) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
            return fallback;
        }
        if (!arguments.get(name).isJsonPrimitive()) {
            return fallback;
        }
        return arguments.get(name).getAsString();
    }

    private static int boundedInt(JsonObject arguments, String name, int fallback, int minimum, int maximum) {
        if (!arguments.has(name) || !arguments.get(name).isJsonPrimitive()) {
            return fallback;
        }
        try {
            int value = arguments.get(name).getAsInt();
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException | UnsupportedOperationException exception) {
            return fallback;
        }
    }

    private static JsonObject envelope() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("content", "");
        result.add("items", new JsonArray());
        result.add("matches", new JsonArray());
        result.addProperty("truncated", false);
        return result;
    }

    private static ToolResult invalid(JsonObject envelope, String code, String message) {
        envelope.addProperty("status", "invalid");
        envelope.addProperty("error_code", code);
        envelope.addProperty("message", message);
        return new ToolResult("invalid", envelope);
    }

    private static ToolResult error(JsonObject envelope, String code, String message) {
        envelope.addProperty("error_code", code);
        envelope.addProperty("message", message);
        String status = code.equals("path_escape") ? "denied" : "recoverable_error";
        envelope.addProperty("status", status);
        return new ToolResult(status, envelope);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + shorten(message, 256));
    }

    private static String shorten(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static long deadline(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
    }

    private static void checkDeadline(long deadline) throws ToolFailure {
        if (System.nanoTime() - deadline >= 0L) {
            throw new ToolFailure("timeout", "文件工具执行超时");
        }
    }

    public record Limits(int maxReadChars, int maxResults, int maxDepth, long timeoutMillis) {
        public Limits {
            if (maxReadChars < 1 || maxResults < 1 || maxDepth < 0 || timeoutMillis < 1L) {
                throw new IllegalArgumentException("file tool limits must be positive");
            }
        }
    }

    private static final class PendingContext {
        private final JsonArray after;
        private int remaining;

        private PendingContext(JsonArray after, int remaining) {
            this.after = after;
            this.remaining = remaining;
        }
    }

    private static final class ToolFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        private ToolFailure(String code, String message) {
            super(message);
            this.code = code.toLowerCase(Locale.ROOT);
        }
    }
}
