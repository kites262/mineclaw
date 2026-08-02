package cc.kites.mineclaw.commandexec;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRequestTest {
    @Test
    void requiresAllThreeFieldsButAcceptsExplicitNullPlayer() {
        JsonObject arguments = request("/Say   Hello", "  announce   hello  ", JsonNull.INSTANCE);

        CommandRequest parsed = CommandRequest.parse(arguments);

        assertThat(parsed.command()).isEqualTo("Say Hello");
        assertThat(parsed.normalizedCommand()).isEqualTo("say hello");
        assertThat(parsed.intent()).isEqualTo("announce hello");
        assertThat(parsed.player()).isEmpty();

        for (String missing : List.of("command", "intent", "player")) {
            JsonObject incomplete = arguments.deepCopy();
            incomplete.remove(missing);
            assertThatThrownBy(() -> CommandRequest.parse(incomplete))
                    .isInstanceOf(CommandRequest.InvalidCommandRequestException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void rejectsWrongTypesBlankTextControlsAndOverlongValues() {
        JsonObject wrongType = request("say hi", "intent", JsonNull.INSTANCE);
        wrongType.addProperty("command", 12);
        assertInvalid(wrongType, "command must be a string");

        assertInvalid(request(" /  ", "intent", JsonNull.INSTANCE), "command must not be blank");
        assertInvalid(request("say hi\nstop", "intent", JsonNull.INSTANCE), "control character");
        assertInvalid(request("say hi", "intent\u0000", JsonNull.INSTANCE), "control character");

        JsonObject playerWhitespace = new JsonObject();
        playerWhitespace.addProperty("command", "home");
        playerWhitespace.addProperty("intent", "go home");
        playerWhitespace.addProperty("player", "Some Player");
        assertInvalid(playerWhitespace, "without whitespace");

        CommandRequest.Limits limits = new CommandRequest.Limits(3, 4, 5);
        assertThatThrownBy(() -> CommandRequest.parse(request("four", "fine", JsonNull.INSTANCE), limits))
                .isInstanceOf(CommandRequest.InvalidCommandRequestException.class)
                .hasMessageContaining("command is longer");
    }

    @Test
    void whitelistUsesRootLocaleAndMatchesTheEntireNormalizedCommand() {
        CommandRequest request = CommandRequest.parse(request("  /I  TEST ", "intent", JsonNull.INSTANCE));
        CommandRules exact = new CommandRules(true, List.of(), List.of(Pattern.compile("i test")));
        CommandRules prefixOnly = new CommandRules(true, List.of(), List.of(Pattern.compile("i")));

        assertThat(request.normalizedCommand()).isEqualTo("i test");
        assertThat(exact.consoleAllowed(request)).isTrue();
        assertThat(prefixOnly.consoleAllowed(request)).isFalse();
    }

    private static JsonObject request(String command, String intent, com.google.gson.JsonElement player) {
        JsonObject result = new JsonObject();
        result.addProperty("command", command);
        result.addProperty("intent", intent);
        result.add("player", player);
        return result;
    }

    private static void assertInvalid(JsonObject value, String message) {
        assertThatThrownBy(() -> CommandRequest.parse(value))
                .isInstanceOf(CommandRequest.InvalidCommandRequestException.class)
                .hasMessageContaining(message);
    }
}
