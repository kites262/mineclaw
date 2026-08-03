package cc.kites.mineclaw.config;

import cc.kites.mineclaw.workspace.WorkspacePathSecurity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared strict YAML-to-JSON boundary for trusted control-plane files. */
final class StrictYaml {
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_CHARS = 1_048_576;
    private static final int MAX_DEPTH = 64;
    private static final Gson GSON = new Gson();

    private StrictYaml() { }

    static JsonObject load(Path dataRoot, Path path, String expectedName) throws ConfigException {
        WorkspacePathSecurity security = new WorkspacePathSecurity(dataRoot);
        final byte[] bytes;
        try {
            Path candidate = security.requireFixedReadable(path, expectedName);
            try (InputStream input = Channels.newInputStream(java.nio.file.Files.newByteChannel(candidate,
                    java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
        } catch (IOException exception) {
            throw new ConfigException("Cannot safely read " + expectedName, exception);
        }
        if (bytes.length > MAX_BYTES) {
            throw new ConfigException(expectedName + " exceeds the 1 MiB byte limit");
        }
        final String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new ConfigException(expectedName + " is not valid UTF-8");
        }
        return parse(source, expectedName);
    }

    static JsonObject parse(String source, String name) throws ConfigException {
        Objects.requireNonNull(source, "source");
        if (source.codePointCount(0, source.length()) > MAX_CHARS) {
            throw new ConfigException(name + " exceeds the 1 MiB character limit");
        }
        Object raw;
        try {
            LoaderOptions options = options(source);
            Node document = new Yaml(new SafeConstructor(options)).compose(new StringReader(source));
            validateNode(document, new IdentityHashMap<>(), 0, name);
            raw = new Yaml(new SafeConstructor(options(source))).load(source);
        } catch (YAMLException | IllegalStateException exception) {
            throw new ConfigException(name + " contains invalid YAML");
        }
        validateJson(raw, "$", 0, new IdentityHashMap<>(), name);
        JsonElement json;
        try {
            json = GSON.toJsonTree(raw);
        } catch (RuntimeException exception) {
            throw new ConfigException(name + " cannot be converted to JSON-compatible data");
        }
        if (!json.isJsonObject()) {
            throw new ConfigException(name + " root must be a mapping");
        }
        return json.getAsJsonObject();
    }

    private static LoaderOptions options(String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(Math.max(1_024, source.codePointCount(0, source.length()) + 1));
        return options;
    }

    private static void validateNode(Node node, IdentityHashMap<Node, Boolean> seen, int depth,
                                     String name) throws ConfigException {
        if (node == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new ConfigException(name + " exceeds maximum YAML depth");
        }
        if (node.getAnchor() != null || seen.put(node, Boolean.TRUE) != null) {
            throw new ConfigException(name + " anchors and aliases are not allowed");
        }
        if (node.getTag().isCustomGlobal()) {
            throw new ConfigException(name + " custom YAML tags are not allowed");
        }
        if (node instanceof MappingNode mapping) {
            if (mapping.isMerged()) {
                throw new ConfigException(name + " merge keys are not allowed");
            }
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode().getTag().equals(Tag.MERGE)
                        || tuple.getKeyNode() instanceof ScalarNode scalar
                        && scalar.getValue().equals("<<")) {
                    throw new ConfigException(name + " merge keys are not allowed");
                }
                validateNode(tuple.getKeyNode(), seen, depth + 1, name);
                validateNode(tuple.getValueNode(), seen, depth + 1, name);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node child : sequence.getValue()) {
                validateNode(child, seen, depth + 1, name);
            }
        }
    }

    private static void validateJson(Object value, String path, int depth,
                                     IdentityHashMap<Object, Boolean> ancestors,
                                     String name) throws ConfigException {
        if (depth > MAX_DEPTH) {
            throw new ConfigException(name + ' ' + path + " exceeds maximum JSON depth");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Float number && Float.isFinite(number)
                || value instanceof Double number && Double.isFinite(number)) {
            return;
        }
        if (!(value instanceof Map<?, ?> || value instanceof List<?>)) {
            throw new ConfigException(name + ' ' + path + " contains a non-JSON YAML value");
        }
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw new ConfigException(name + ' ' + path + " contains a recursive value");
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ConfigException(name + ' ' + path + " contains a non-string mapping key");
                }
                validateJson(entry.getValue(), path + '.' + key, depth + 1, ancestors, name);
            }
        } else {
            List<?> list = (List<?>) value;
            for (int index = 0; index < list.size(); index++) {
                validateJson(list.get(index), path + '[' + index + ']', depth + 1, ancestors, name);
            }
        }
        ancestors.remove(value);
    }
}
