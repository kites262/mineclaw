package cc.kites.mineclaw.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaCompilerTest {
    @Test
    void compilesOnceAndValidatesAllSupportedRuntimeConstraints() {
        CompiledSchema schema = compile("""
                {
                  "type":"object",
                  "properties":{
                    "label":{"type":"string","minLength":2,"maxLength":3,"enum":["ab","😀x"]},
                    "count":{"type":"integer","minimum":1,"maximum":3},
                    "ratio":{"type":"number","minimum":0.25,"maximum":2.5},
                    "flags":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"boolean"}},
                    "child":{
                      "type":"object",
                      "properties":{"nothing":{"type":"null"}},
                      "required":["nothing"],
                      "additionalProperties":false
                    }
                  },
                  "required":["label","count","ratio","flags","child"],
                  "additionalProperties":false
                }
                """);

        assertThat(schema.validate(json("""
                {"label":"😀x","count":2.0,"ratio":1.5,"flags":[true],"child":{"nothing":null}}
                """)).valid()).isTrue();

        ValidationResult invalid = schema.validate(json("""
                {"label":"x","count":4,"ratio":0.1,"flags":[],"child":{},"extra":true}
                """));
        assertThat(invalid.violations())
                .extracting(SchemaViolation::path, SchemaViolation::keyword)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("$.label", "enum"),
                        org.assertj.core.groups.Tuple.tuple("$.label", "minLength"),
                        org.assertj.core.groups.Tuple.tuple("$.count", "maximum"),
                        org.assertj.core.groups.Tuple.tuple("$.ratio", "minimum"),
                        org.assertj.core.groups.Tuple.tuple("$.flags", "minItems"),
                        org.assertj.core.groups.Tuple.tuple("$.child.nothing", "required"),
                        org.assertj.core.groups.Tuple.tuple("$.extra", "additionalProperties"));
    }

    @Test
    void emitsStableArrayJsonPathsAndCapsViolations() {
        CompiledSchema schema = SchemaCompiler.compile(object("""
                {
                  "type":"object",
                  "properties":{
                    "values":{"type":"array","items":{"type":"integer","minimum":1}}
                  },
                  "required":["values"],
                  "additionalProperties":false
                }
                """), new SchemaLimits(1_000, 8, 100, 2));

        ValidationResult result = schema.validate(json("{" +
                "\"values\":[0,\"bad\",0]}"));

        assertThat(result.violations()).containsExactly(
                new SchemaViolation("$.values[0]", "minimum", "must be greater than or equal to 1"),
                new SchemaViolation("$.values[1]", "type", "must be of type integer"));
    }

    @Test
    void rejectsUnsupportedOrMisappliedKeywordsAndNonObjectRoot() {
        assertCompileFailure("""
                {"type":"object","additionalProperties":false,"pattern":"x"}
                """, "unsupported Schema keyword");
        assertCompileFailure("""
                {"type":"array","items":{"type":"string"}}
                """, "root Schema type must be object");
        assertCompileFailure("""
                {"type":"object","properties":{"x":{"type":"string","minimum":1}},"additionalProperties":false}
                """, "minimum is only valid for integer or number");
        assertCompileFailure("""
                {"type":["object","null"],"additionalProperties":false}
                """, "type must be one supported string");
    }

    @Test
    void enforcesStrictObjectPropertyAndRequiredRules() {
        assertCompileFailure("""
                {"type":"object","properties":{}}
                """, "additionalProperties must be explicitly false");
        assertCompileFailure("""
                {"type":"object","properties":{"bad-name":{"type":"string"}},"additionalProperties":false}
                """, "property name must match");
        assertCompileFailure("""
                {"type":"object","properties":{"x":{"type":"string"}},"required":["missing"],"additionalProperties":false}
                """, "required references missing property missing");
        assertCompileFailure("""
                {"type":"object","properties":{"x":{"type":"string"}},"required":["x","x"],"additionalProperties":false}
                """, "required must contain unique property names");
    }

    @Test
    void rejectsInvalidRangesLengthsAndEnumsAtDefinitionTime() {
        assertCompileFailure(wrapProperty("{" +
                "\"type\":\"number\",\"minimum\":2,\"maximum\":1}"),
                "minimum must not exceed maximum");
        assertCompileFailure(wrapProperty("{" +
                "\"type\":\"string\",\"minLength\":2,\"maxLength\":1}"),
                "minLength must not exceed maxLength");
        assertCompileFailure(wrapProperty("{" +
                "\"type\":\"array\",\"minItems\":-1}"),
                "minItems must be a non-negative JSON integer");
        assertCompileFailure(wrapProperty("{" +
                "\"type\":\"integer\",\"enum\":[1,1.0]}"),
                "enum values must be unique");
        assertCompileFailure(wrapProperty("{" +
                "\"type\":\"string\",\"enum\":[1]}"),
                "enum value does not match declared type");
    }

    @Test
    void appliesDepthMemberAndCharacterBudgetsBeforeSchemaTraversal() {
        CompiledSchema depth = SchemaCompiler.compile(object("""
                {
                  "type":"object",
                  "properties":{"nested":{"type":"array","items":{"type":"array"}}},
                  "additionalProperties":false
                }
                """), new SchemaLimits(100, 2, 10, 8));
        assertThat(depth.validate(json("{" +
                "\"nested\":[[[1]]]}" )).violations().getFirst().keyword()).isEqualTo("maxDepth");

        CompiledSchema members = SchemaCompiler.compile(object("""
                {"type":"object","additionalProperties":false}
                """), new SchemaLimits(100, 4, 6, 8));
        assertThat(members.validate(json("""
                {"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7}
                """)).violations().getFirst().keyword()).isEqualTo("maxMembers");

        CompiledSchema chars = SchemaCompiler.compile(object("""
                {"type":"object","properties":{"x":{"type":"string"}},"additionalProperties":false}
                """), new SchemaLimits(100, 4, 10, 8));
        assertThat(chars.validate(json("{\"x\":\"" + "a".repeat(120) + "\"}"))
                .violations().getFirst().keyword()).isEqualTo("maxChars");
    }

    @Test
    void compilerAlsoAppliesDefinitionBudgets() {
        assertThatThrownBy(() -> SchemaCompiler.compile(object("""
                {"type":"object","properties":{"x":{"type":"string"}},"additionalProperties":false}
                """), new SchemaLimits(10, 4, 100, 8)))
                .isInstanceOf(SchemaCompilationException.class)
                .hasMessageContaining("maximum Schema character count exceeded");
    }

    private static CompiledSchema compile(String source) {
        return SchemaCompiler.compile(object(source));
    }

    private static JsonObject object(String source) {
        return JsonParser.parseString(source).getAsJsonObject();
    }

    private static JsonElement json(String source) {
        return JsonParser.parseString(source);
    }

    private static void assertCompileFailure(String source, String message) {
        assertThatThrownBy(() -> compile(source))
                .isInstanceOf(SchemaCompilationException.class)
                .hasMessageContaining(message);
    }

    private static String wrapProperty(String propertySchema) {
        return "{\"type\":\"object\",\"properties\":{\"value\":" + propertySchema
                + "},\"additionalProperties\":false}";
    }
}
