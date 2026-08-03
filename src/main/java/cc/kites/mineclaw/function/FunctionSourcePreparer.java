package cc.kites.mineclaw.function;

import cc.kites.mineclaw.javascript.SourceValidation;

import java.util.Objects;

/** Prepares one JavaScript source without executing onCall. */
@FunctionalInterface
public interface FunctionSourcePreparer {
    SourceValidation prepare(String functionName, int apiVersion, String source);

    static FunctionSourcePreparer unavailable() {
        return (functionName, apiVersion, source) -> {
            Objects.requireNonNull(functionName, "functionName");
            Objects.requireNonNull(source, "source");
            return SourceValidation.invalid(
                    "javascript_runtime_unavailable", "JavaScript runtime is unavailable");
        };
    }
}
