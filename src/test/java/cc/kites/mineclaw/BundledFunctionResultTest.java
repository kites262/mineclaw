package cc.kites.mineclaw;

import cc.kites.mineclaw.javascript.InvocationRequest;
import cc.kites.mineclaw.javascript.JavaScriptLimits;
import cc.kites.mineclaw.javascript.JavaScriptWorkflowRuntime;
import cc.kites.mineclaw.javascript.OperationHandle;
import cc.kites.mineclaw.javascript.OperationResult;
import cc.kites.mineclaw.javascript.PreparedScript;
import cc.kites.mineclaw.javascript.ScriptResult;
import cc.kites.mineclaw.javascript.SourceValidation;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BundledFunctionResultTest {
    private static final Set<String> CAPABILITIES = Set.of(
            "approval.request", "command.dispatch.console");

    @Test
    void preservesTheOriginalDispatchFailureAndApprovedContext() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(
                JavaScriptLimits.defaults())) {
            PreparedScript script = bundledScript(runtime);

            ScriptResult result = runtime.execute(script, request(), call -> {
                if (call.action().equals("approval.request")) {
                    JsonObject output = new JsonObject();
                    output.addProperty("value", true);
                    return OperationHandle.completed(new OperationResult("approved", output));
                }
                JsonObject output = new JsonObject();
                output.addProperty("error_code", "command_not_found");
                output.addProperty("message", "command was not found");
                output.addProperty("dispatch_status", "command_not_found");
                output.addProperty("execution_result", "not_started");
                output.addProperty("feedback", "Unknown command");
                return OperationHandle.completed(new OperationResult("terminal_error", output));
            }).result().get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo("recoverable_error");
            assertThat(result.output().get("error_code").getAsString())
                    .isEqualTo("command_not_found");
            assertThat(result.output().get("message").getAsString())
                    .isEqualTo("command was not found");
            assertThat(result.output().get("approval_status").getAsString())
                    .isEqualTo("approved");
            assertThat(result.output().get("operation_status").getAsString())
                    .isEqualTo("terminal_error");
            assertThat(result.output().get("dispatch_status").getAsString())
                    .isEqualTo("command_not_found");
            assertThat(result.output().get("execution_result").getAsString())
                    .isEqualTo("not_started");
            assertThat(result.output().get("feedback").getAsString())
                    .isEqualTo("Unknown command");
            assertThat(result.output().get("executor").getAsString()).isEqualTo("console");
            assertThat(result.output().get("target_player").getAsString())
                    .isEqualTo("ExactPlayer");
            assertThat(result.output().get("effect").getAsString())
                    .isEqualTo("minecraft:speed");
            assertThat(result.output().get("duration_seconds").getAsInt()).isEqualTo(30);
        }
    }

    @Test
    void preservesTheOriginalApprovalFailureAndRequestContext() throws Exception {
        try (JavaScriptWorkflowRuntime runtime = new JavaScriptWorkflowRuntime(
                JavaScriptLimits.defaults())) {
            PreparedScript script = bundledScript(runtime);
            ScriptResult result = runtime.execute(script, request(), call -> {
                JsonObject output = new JsonObject();
                output.add("value", JsonNull.INSTANCE);
                output.addProperty("error_code", "approval_timeout");
                output.addProperty("message", "approval request timed out");
                return OperationHandle.completed(new OperationResult("timeout", output));
            }).result().get(5, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo("denied");
            assertThat(result.output().get("error_code").getAsString())
                    .isEqualTo("approval_timeout");
            assertThat(result.output().get("message").getAsString())
                    .isEqualTo("approval request timed out");
            assertThat(result.output().get("approval_status").getAsString())
                    .isEqualTo("timeout");
            assertThat(result.output().get("target_player").getAsString())
                    .isEqualTo("ExactPlayer");
            assertThat(result.output().get("effect_name").getAsString()).isEqualTo("速度");
            assertThat(result.output().get("duration_seconds").getAsInt()).isEqualTo(30);
        }
    }

    private static PreparedScript bundledScript(JavaScriptWorkflowRuntime runtime) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream stream = BundledFunctionResultTest.class
                .getResourceAsStream("/functions.yml")) {
            assertThat(stream).isNotNull();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        Map<?, ?> definition = yaml.getMapList("functions").getFirst();
        String name = (String) definition.get("name");
        String source = (String) definition.get("on_call");
        SourceValidation validation = runtime.validateSource(name, 1, source);
        assertThat(validation.diagnostic()).isEmpty();
        return validation.script().orElseThrow();
    }

    private static InvocationRequest request() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("effect", "minecraft:speed");
        arguments.addProperty("duration_seconds", 30);
        return new InvocationRequest(UUID.randomUUID().toString(), "ExactPlayer",
                arguments, CAPABILITIES);
    }
}
