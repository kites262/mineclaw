package cc.kites.mineclaw.javascript;

import java.util.Objects;

/** Stable, operator-facing source validation diagnostic. */
public record ScriptDiagnostic(String code, String message) {
    public ScriptDiagnostic {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }
}
