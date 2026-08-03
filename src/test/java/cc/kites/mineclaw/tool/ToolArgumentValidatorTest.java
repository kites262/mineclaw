package cc.kites.mineclaw.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentValidatorTest {
    @Test
    void validatesNestedObjectsArraysRequiredPropertiesAndTypeUnions() {
        JsonObject schema = object("""
                {
                  "type": "object",
                  "properties": {
                    "entries": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {"count": {"type": "integer"}},
                        "required": ["count"],
                        "additionalProperties": false
                      }
                    },
                    "label": {"type": ["string", "null"]}
                  },
                  "required": ["entries", "label"],
                  "additionalProperties": false
                }
                """);

        assertThat(ToolArgumentValidator.validate(object("""
                {"entries":[{"count":2}],"label":null}
                """), schema)).isEmpty();

        ToolArgumentValidator.Violation wrongItem = ToolArgumentValidator.validate(object("""
                {"entries":[{"count":"2"}],"label":"ok"}
                """), schema).orElseThrow();
        assertThat(wrongItem.path()).isEqualTo("$.entries[0].count");
        assertThat(wrongItem.message()).isEqualTo("expected integer but found string");

        ToolArgumentValidator.Violation missing = ToolArgumentValidator.validate(object("""
                {"entries":[]}
                """), schema).orElseThrow();
        assertThat(missing.path()).isEqualTo("$.label");
        assertThat(missing.message()).contains("required");
    }

    @Test
    void enforcesBooleanAndSchemaValuedAdditionalProperties() {
        JsonObject closed = object("""
                {"type":"object","properties":{},"additionalProperties":false}
                """);
        ToolArgumentValidator.Violation unexpected = ToolArgumentValidator.validate(
                object("{\"surprise\":true}"), closed).orElseThrow();
        assertThat(unexpected.path()).isEqualTo("$.surprise");
        assertThat(unexpected.message()).contains("not allowed");

        JsonObject booleanValues = object("""
                {"type":"object","additionalProperties":{"type":"boolean"}}
                """);
        assertThat(ToolArgumentValidator.validate(object("{\"enabled\":true}"), booleanValues)).isEmpty();
        assertThat(ToolArgumentValidator.validate(object("{\"enabled\":1}"), booleanValues)
                .orElseThrow().path()).isEqualTo("$.enabled");
    }

    @Test
    void distinguishesIntegersFromFractionalNumbers() {
        JsonObject schema = object("""
                {"type":"object","properties":{"value":{"type":"integer"}}}
                """);
        assertThat(ToolArgumentValidator.validate(object("{\"value\":1e3}"), schema)).isEmpty();
        assertThat(ToolArgumentValidator.validate(object("{\"value\":1.5}"), schema)
                .orElseThrow().message()).isEqualTo("expected integer but found number");
    }

    @Test
    void enforcesNumericBoundsDeclaredByTheBundledToolSchemas() {
        JsonObject schema = object("""
                {"type":"object","properties":{"depth":{"type":"integer","minimum":0,"maximum":4}}}
                """);

        assertThat(ToolArgumentValidator.validate(object("{\"depth\":0}"), schema)).isEmpty();
        assertThat(ToolArgumentValidator.validate(object("{\"depth\":-1}"), schema)
                .orElseThrow().message()).contains("greater than or equal to 0");
        assertThat(ToolArgumentValidator.validate(object("{\"depth\":5}"), schema)
                .orElseThrow().message()).contains("less than or equal to 4");
    }

    @Test
    void enforcesEnumsRecursivelyUsingJsonNumericValueEquality() {
        JsonObject schema = object("""
                {
                  "type":"object",
                  "properties":{
                    "mode":{"type":"string","enum":["safe","fast"]},
                    "amount":{"type":"number","enum":[1,2.5]},
                    "optional":{"type":["boolean","null"],"enum":[true,null]}
                  },
                  "required":["mode","amount","optional"],
                  "additionalProperties":false
                }
                """);

        assertThat(ToolArgumentValidator.validate(
                object("{\"mode\":\"safe\",\"amount\":1.0,\"optional\":null}"), schema)).isEmpty();

        ToolArgumentValidator.Violation violation = ToolArgumentValidator.validate(
                object("{\"mode\":\"unsafe\",\"amount\":1,\"optional\":true}"), schema).orElseThrow();
        assertThat(violation.path()).isEqualTo("$.mode");
        assertThat(violation.message()).contains("enum");
    }

    @Test
    void rejectsMalformedRuntimeEnumSchemasInsteadOfTreatingThemAsHints() {
        JsonObject empty = object("""
                {"type":"object","properties":{"value":{"type":"string","enum":[]}}}
                """);
        JsonObject composite = object("""
                {"type":"object","properties":{"value":{"type":"string","enum":[{}]}}}
                """);

        assertThat(ToolArgumentValidator.validate(object("{\"value\":\"x\"}"), empty)
                .orElseThrow().message()).contains("non-empty");
        assertThat(ToolArgumentValidator.validate(object("{\"value\":\"x\"}"), composite)
                .orElseThrow().message()).contains("JSON scalars");
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
