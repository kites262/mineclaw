package cc.kites.mineclaw.function;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, generation-scoped Function registry that never projects model Tool JSON. */
public record FunctionCatalog(
        long generation,
        List<FunctionDefinition> definitions,
        List<String> diagnostics
) {
    public FunctionCatalog {
        if (generation < 1L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static FunctionCatalog empty(long generation, String diagnostic) {
        return new FunctionCatalog(generation, List.of(),
                diagnostic == null ? List.of() : List.of(diagnostic));
    }

    public List<FunctionDefinition> enabledDefinitions() {
        return definitions.stream().filter(FunctionDefinition::available).toList();
    }

    public List<FunctionDefinition> invalidDefinitions() {
        return definitions.stream()
                .filter(definition -> definition.status() == FunctionDefinition.Status.INVALID)
                .toList();
    }

    /** Exact, case-sensitive lookup including disabled and invalid entries. */
    public Optional<FunctionDefinition> find(String name) {
        Objects.requireNonNull(name, "name");
        return definitions.stream().filter(definition -> definition.name().equals(name)).findFirst();
    }

    /** Exact lookup that deliberately makes unknown, disabled, invalid, and duplicates unavailable. */
    public Optional<FunctionDefinition> findEnabled(String name) {
        Objects.requireNonNull(name, "name");
        return definitions.stream()
                .filter(definition -> definition.name().equals(name))
                .filter(FunctionDefinition::available)
                .findFirst();
    }
}
