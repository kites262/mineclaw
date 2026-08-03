package cc.kites.mineclaw.workspace;

import com.google.gson.JsonArray;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A complete per-request tools.yml snapshot, retaining invalid entries for diagnostics. */
public record ToolCatalog(List<ToolDefinition> definitions, List<String> diagnostics) {
    public ToolCatalog {
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static ToolCatalog empty(String diagnostic) {
        return new ToolCatalog(List.of(), diagnostic == null ? List.of() : List.of(diagnostic));
    }

    public List<ToolDefinition> enabledDefinitions() {
        return definitions.stream().filter(ToolDefinition::available).toList();
    }

    public List<ToolDefinition> invalidDefinitions() {
        return definitions.stream().filter(tool -> tool.status() == ToolDefinition.Status.INVALID).toList();
    }

    public Optional<ToolDefinition> find(String handler) {
        Objects.requireNonNull(handler, "handler");
        return definitions.stream().filter(tool -> tool.handler().equals(handler)).findFirst();
    }

    public Optional<ToolDefinition> findEnabled(String handler) {
        Objects.requireNonNull(handler, "handler");
        return definitions.stream()
                .filter(tool -> tool.handler().equals(handler))
                .filter(ToolDefinition::available)
                .findFirst();
    }

    public JsonArray toChatCompletionsTools() {
        JsonArray result = new JsonArray();
        enabledDefinitions().forEach(tool -> result.add(tool.toChatCompletionsTool()));
        return result;
    }
}
