package cc.kites.mineclaw.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.logging.Logger;

/** Emits stable, single-line command audit records without log injection. */
public final class AuditLogger {
    private final Logger logger;

    public AuditLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void command(String action, Map<String, ?> fields) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("time", Instant.now());
        record.putAll(fields);
        log("command." + action, record);
    }

    public void log(String action, Map<String, ?> fields) {
        StringJoiner line = new StringJoiner(" ", "[AUDIT] ", "");
        line.add("action=" + quote(action));
        fields.forEach((key, value) -> line.add(safeKey(key) + "=" + quote(String.valueOf(value))));
        logger.info(line.toString());
    }

    private static String safeKey(String key) {
        return key.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String quote(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (codePoint == '\\') {
                normalized.append("\\\\");
            } else if (codePoint == '"') {
                normalized.append("\\\"");
            } else if (codePoint == '\r') {
                normalized.append("\\r");
            } else if (codePoint == '\n') {
                normalized.append("\\n");
            } else if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                normalized.append("\\u").append(String.format(java.util.Locale.ROOT, "%04X", codePoint));
            } else {
                normalized.appendCodePoint(codePoint);
            }
        });
        return '"' + normalized.toString() + '"';
    }
}
