package cc.kites.mineclaw.schema;

import java.util.List;
import java.util.Objects;

/** Immutable result of validating one JSON value against a CompiledSchema. */
public record ValidationResult(List<SchemaViolation> violations) {
    public ValidationResult {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    public boolean valid() {
        return violations.isEmpty();
    }

    public boolean isValid() {
        return valid();
    }

    public static ValidationResult success() {
        return new ValidationResult(List.of());
    }
}
