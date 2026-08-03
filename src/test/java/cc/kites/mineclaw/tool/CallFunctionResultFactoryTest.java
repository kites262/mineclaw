package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.javascript.ScriptResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallFunctionResultFactoryTest {
    @Test
    void everyFailureUsesTheExactThreeMemberEnvelope() {
        ToolResult result = CallFunctionResultFactory.invalidCall(null);

        assertThat(result.status()).isEqualTo("invalid");
        assertThat(result.output().keySet()).containsExactlyInAnyOrder("status", "function", "output");
        assertThat(result.output().get("function").isJsonNull()).isTrue();
        assertThat(result.output().getAsJsonObject("output").get("error_code").getAsString())
                .isEqualTo("invalid_call_arguments");
    }

    @Test
    void argumentViolationsStayInsideOutput() {
        JsonObject violation = new JsonObject();
        violation.addProperty("path", "$.duration_seconds");
        violation.addProperty("keyword", "maximum");
        violation.addProperty("message", "must be <= 300");
        JsonArray violations = new JsonArray();
        violations.add(violation);

        ToolResult result = CallFunctionResultFactory.invalidArguments("player.effect.give", violations);

        assertThat(result.output().get("function").getAsString()).isEqualTo("player.effect.give");
        assertThat(result.output().getAsJsonObject("output").getAsJsonArray("violations")).hasSize(1);
    }

    @Test
    void rejectsScriptOutputThatRepeatsEnvelopeMembersOrMissesFailureDetails() {
        JsonObject repeated = new JsonObject();
        repeated.addProperty("status", "ok");
        ToolResult first = CallFunctionResultFactory.fromScript(
                "example.echo", new ScriptResult("ok", repeated));
        ToolResult second = CallFunctionResultFactory.fromScript(
                "example.echo", new ScriptResult("denied", new JsonObject()));

        assertThat(first.output().getAsJsonObject("output").get("error_code").getAsString())
                .isEqualTo("invalid_script_result");
        assertThat(second.output().getAsJsonObject("output").get("error_code").getAsString())
                .isEqualTo("invalid_script_result");
    }
}
