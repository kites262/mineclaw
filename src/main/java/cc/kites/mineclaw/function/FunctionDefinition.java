package cc.kites.mineclaw.function;

import cc.kites.mineclaw.javascript.PreparedScript;
import cc.kites.mineclaw.schema.CompiledSchema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One functions.yml entry, including unavailable entries retained for administrator diagnostics. */
public record FunctionDefinition(
        int index,
        String name,
        String description,
        boolean declaredEnabled,
        Status status,
        Optional<String> diagnostic,
        Optional<CompiledSchema> compiledParameters,
        List<String> capabilities,
        Optional<PreparedScript> preparedSource,
        Optional<String> scriptHash,
        int apiVersion
) {
    public FunctionDefinition {
        if (index < 1) {
            throw new IllegalArgumentException("index must be one-based");
        }
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNull(description, "description");
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        compiledParameters = Objects.requireNonNull(compiledParameters, "compiledParameters");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        capabilities.forEach(capability -> Objects.requireNonNull(capability, "capability"));
        preparedSource = Objects.requireNonNull(preparedSource, "preparedSource");
        scriptHash = Objects.requireNonNull(scriptHash, "scriptHash");
        if (apiVersion < 1) {
            throw new IllegalArgumentException("apiVersion must be positive");
        }
        if (status == Status.INVALID && diagnostic.isEmpty()) {
            throw new IllegalArgumentException("invalid functions require a diagnostic");
        }
        if (status != Status.INVALID
                && (compiledParameters.isEmpty() || preparedSource.isEmpty() || scriptHash.isEmpty())) {
            throw new IllegalArgumentException("valid functions require compiled Schema and prepared source");
        }
        scriptHash.ifPresent(hash -> {
            if (!hash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("scriptHash must be a prefixed lowercase SHA-256 value");
            }
        });
        if (preparedSource.isPresent() && scriptHash.isPresent()) {
            PreparedScript prepared = preparedSource.orElseThrow();
            if (!prepared.scriptHash().equals(scriptHash.orElseThrow())) {
                throw new IllegalArgumentException("prepared source hash must match scriptHash");
            }
            if (!prepared.functionName().equals(name) || prepared.apiVersion() != apiVersion) {
                throw new IllegalArgumentException("prepared source identity must match the Function");
            }
        }
    }

    public boolean available() {
        return status == Status.ENABLED;
    }

    public String printableName() {
        return name.isBlank() ? "entry #" + index : name;
    }

    public Optional<String> shortScriptHash() {
        return scriptHash.map(hash -> hash.substring("sha256:".length(), "sha256:".length() + 12));
    }

    public CompiledSchema requireCompiledParameters() {
        if (!available()) {
            throw new IllegalStateException("Function " + printableName() + " is not enabled");
        }
        return compiledParameters.orElseThrow();
    }

    public PreparedScript requirePreparedSource() {
        if (!available()) {
            throw new IllegalStateException("Function " + printableName() + " is not enabled");
        }
        return preparedSource.orElseThrow();
    }

    FunctionDefinition duplicateInvalid(String duplicateDiagnostic) {
        return new FunctionDefinition(index, name, description, declaredEnabled, Status.INVALID,
                Optional.of(duplicateDiagnostic), compiledParameters, capabilities, preparedSource,
                scriptHash, apiVersion);
    }

    static FunctionDefinition invalid(
            int index,
            String name,
            String description,
            boolean declaredEnabled,
            String diagnostic,
            Optional<CompiledSchema> compiledParameters,
            List<String> capabilities,
            Optional<PreparedScript> preparedSource,
            Optional<String> scriptHash,
            int apiVersion
    ) {
        return new FunctionDefinition(index, name, description, declaredEnabled, Status.INVALID,
                Optional.of(diagnostic), compiledParameters, capabilities, preparedSource, scriptHash,
                apiVersion);
    }

    static FunctionDefinition valid(
            int index,
            String name,
            String description,
            boolean enabled,
            CompiledSchema compiledParameters,
            List<String> capabilities,
            PreparedScript preparedSource,
            String scriptHash,
            int apiVersion
    ) {
        return new FunctionDefinition(index, name, description, enabled,
                enabled ? Status.ENABLED : Status.DISABLED,
                enabled ? Optional.empty() : Optional.of("disabled by the functions.yml entry"),
                Optional.of(compiledParameters), capabilities, Optional.of(preparedSource),
                Optional.of(scriptHash), apiVersion);
    }

    public enum Status {
        ENABLED,
        DISABLED,
        INVALID
    }
}
