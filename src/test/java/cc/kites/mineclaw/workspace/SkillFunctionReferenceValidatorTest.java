package cc.kites.mineclaw.workspace;

import cc.kites.mineclaw.function.FunctionCatalog;
import cc.kites.mineclaw.function.FunctionDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator.Availability.DISABLED;
import static cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator.Availability.ENABLED;
import static cc.kites.mineclaw.workspace.SkillFunctionReferenceValidator.Availability.INVALID;
import static org.assertj.core.api.Assertions.assertThat;

class SkillFunctionReferenceValidatorTest {
    private final SkillFunctionReferenceValidator validator = new SkillFunctionReferenceValidator();

    @Test
    void reportsMissingUnavailableDuplicateAndUnreferencedFunctions(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("workflow.md"), """
                ---
                id: workflow
                functions:
                  - enabled.function
                  - disabled.function
                  - invalid.function
                  - missing.function
                  - enabled.function
                ---
                # Workflow
                """);
        LinkedHashMap<String, SkillFunctionReferenceValidator.Availability> functions =
                new LinkedHashMap<>();
        functions.put("enabled.function", ENABLED);
        functions.put("orphan.function", ENABLED);
        functions.put("disabled.function", DISABLED);
        functions.put("invalid.function", INVALID);

        SkillFunctionReferenceValidator.Report report = validator.validate(directory, functions);

        assertThat(report.referencedFunctions()).containsExactlyInAnyOrder(
                "enabled.function", "disabled.function", "invalid.function", "missing.function");
        assertThat(report.diagnostics())
                .extracting(SkillFunctionReferenceValidator.Diagnostic::code)
                .containsExactly(
                        "skill_function_disabled",
                        "skill_function_invalid",
                        "skill_function_missing",
                        "duplicate_skill_function_reference",
                        "enabled_function_unreferenced");
        assertThat(report.diagnostics().getLast().functionName()).isEqualTo("orphan.function");
    }

    @Test
    void isolatesMalformedSkillMetadataAndContinuesScanning(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a-invalid.md"), """
                ---
                functions: enabled.function
                ---
                """);
        Files.writeString(directory.resolve("b-duplicate-key.md"), """
                ---
                functions: [enabled.function]
                functions: [other.function]
                ---
                """);
        Files.writeString(directory.resolve("c-valid.md"), """
                ---
                optional_metadata:
                functions: [enabled.function]
                ---
                """);

        SkillFunctionReferenceValidator.Report report = validator.validate(
                directory, Map.of("enabled.function", ENABLED));

        assertThat(report.referencedFunctions()).containsExactly("enabled.function");
        assertThat(report.diagnostics())
                .extracting(SkillFunctionReferenceValidator.Diagnostic::code)
                .containsExactly("invalid_skill_functions", "invalid_skill_frontmatter");
        assertThat(report.diagnostics())
                .extracting(SkillFunctionReferenceValidator.Diagnostic::skillPath)
                .containsExactly("a-invalid.md", "b-duplicate-key.md");
    }

    @Test
    void rejectsInvalidReferenceValuesWithoutTreatingThemAsCatalogReferences(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("invalid-values.md"), """
                ---
                functions:
                  - UPPER.case
                  - 42
                  - good.function
                ---
                """);

        SkillFunctionReferenceValidator.Report report = validator.validate(
                directory, Map.of("good.function", ENABLED));

        assertThat(report.referencedFunctions()).containsExactly("good.function");
        assertThat(report.diagnostics())
                .extracting(SkillFunctionReferenceValidator.Diagnostic::code)
                .containsExactly("invalid_skill_function_reference", "invalid_skill_function_reference");
    }

    @Test
    void missingSkillsDirectoryWarnsForEveryEnabledFunction(@TempDir Path directory) {
        Path missing = directory.resolve("skills");

        SkillFunctionReferenceValidator.Report report = validator.validate(
                missing, Map.of("enabled.function", ENABLED, "disabled.function", DISABLED));

        assertThat(Files.exists(missing)).isFalse();
        assertThat(report.referencedFunctions()).isEmpty();
        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("enabled_function_unreferenced");
            assertThat(diagnostic.functionName()).isEqualTo("enabled.function");
        });
    }

    @Test
    void skipsSymbolicLinkSkills(@TempDir Path directory) throws Exception {
        Path outside = Files.createTempFile("mineclaw-skill", ".md");
        Files.writeString(outside, "---\nfunctions: [outside.function]\n---\n");
        try {
            Files.createSymbolicLink(directory.resolve("outside.md"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        SkillFunctionReferenceValidator.Report report = validator.validate(
                directory, Map.of("outside.function", ENABLED));

        assertThat(report.referencedFunctions()).isEmpty();
        assertThat(report.diagnostics()).singleElement()
                .extracting(SkillFunctionReferenceValidator.Diagnostic::code)
                .isEqualTo("enabled_function_unreferenced");
    }

    @Test
    void adaptsTheImmutableFunctionCatalogWithoutExposingEntryDiagnostics(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("workflow.md"),
                "---\nfunctions: [invalid.function]\n---\n");
        FunctionDefinition invalid = new FunctionDefinition(
                1, "invalid.function", "admin description", true,
                FunctionDefinition.Status.INVALID, Optional.of("SECRET_INTERNAL_DIAGNOSTIC"),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), 1);
        FunctionCatalog catalog = new FunctionCatalog(7, List.of(invalid),
                List.of("SECRET_INTERNAL_DIAGNOSTIC"));

        SkillFunctionReferenceValidator.Report report = validator.validate(directory, catalog);

        assertThat(report.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("skill_function_invalid");
            assertThat(diagnostic.functionName()).isEqualTo("invalid.function");
            assertThat(diagnostic.message()).doesNotContain("SECRET_INTERNAL_DIAGNOSTIC");
        });
    }
}
