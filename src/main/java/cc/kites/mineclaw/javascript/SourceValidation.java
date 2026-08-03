package cc.kites.mineclaw.javascript;

import java.util.Objects;
import java.util.Optional;

/** Result of validating and preparing one immutable JavaScript source snapshot. */
public record SourceValidation(
        Optional<PreparedScript> script,
        Optional<ScriptDiagnostic> diagnostic
) {
    public SourceValidation {
        script = Objects.requireNonNull(script, "script");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        if (script.isPresent() == diagnostic.isPresent()) {
            throw new IllegalArgumentException("validation must contain exactly one result");
        }
    }

    public boolean valid() {
        return script.isPresent();
    }

    public static SourceValidation valid(PreparedScript script) {
        return new SourceValidation(Optional.of(Objects.requireNonNull(script, "script")), Optional.empty());
    }

    public static SourceValidation invalid(String code, String message) {
        return new SourceValidation(Optional.empty(),
                Optional.of(new ScriptDiagnostic(code, message)));
    }
}
