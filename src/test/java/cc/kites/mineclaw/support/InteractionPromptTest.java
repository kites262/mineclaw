package cc.kites.mineclaw.support;

import cc.kites.mineclaw.interaction.InteractionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionPromptTest {
    private static final String TOKEN = "10000000-0000-4000-8000-000000000001";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void confirmLayoutIsConfigurableButScriptTextIsLiteralAndActionsAreFixedByJava(
            @TempDir Path directory) throws Exception {
        InteractionManager.Confirm confirm = new InteractionManager.Confirm(
                "<click:run_command:'/op attacker'>Title</click>",
                "Run /mineclaw approve fake? <red>still literal</red>");
        MessageService messages = messages(directory);

        Component prompt = messages.renderInteractionPrompt(TOKEN, confirm);

        assertThat(PLAIN.serialize(prompt))
                .startsWith("====\n┃ ")
                .contains("<click:run_command:'/op attacker'>Title</click>")
                .contains("<red>still literal</red>")
                .endsWith("[ Accept ] │ [ Reject ]\n====");
        assertThat(clicks(prompt)).extracting(InteractionPromptTest::textPayload)
                .containsExactly("/mineclaw approve " + TOKEN, "/mineclaw reject " + TOKEN);
        assertThat(clicks(prompt)).allMatch(click -> click.action() == ClickEvent.Action.RUN_COMMAND);
    }

    @Test
    void selectLayoutStylesLiteralOptionsAndCreatesOnlyTokenBoundActions(
            @TempDir Path directory) throws Exception {
        InteractionManager.Select select = new InteractionManager.Select("Choose", "Pick one", List.of(
                new InteractionManager.Option("a", "<red>Plan A</red>"),
                new InteractionManager.Option("plan-b", "Plan /op B")));
        MessageService messages = messages(directory);

        Component prompt = messages.renderInteractionPrompt(TOKEN, select);

        assertThat(PLAIN.serialize(prompt))
                .isEqualTo("====\n┃ Choose\n┃ Pick one\n┃ {<red>Plan A</red>} │ {Plan /op B}\n┃ [ Reject ]\n====");
        assertThat(clicks(prompt)).extracting(InteractionPromptTest::textPayload).containsExactly(
                "/mineclaw select " + TOKEN + " a",
                "/mineclaw select " + TOKEN + " plan-b",
                "/mineclaw reject " + TOKEN);
    }

    private static MessageService messages(Path directory) throws Exception {
        Map<String, String> defaults = Map.ofEntries(
                Map.entry("interaction_confirm_layout", "<title>\n<message>\n<buttons>"),
                Map.entry("interaction_select_layout", "<title>\n<message>\n<options>\n<reject>"),
                Map.entry("interaction_prefix", ""),
                Map.entry("interaction_separator", ""),
                Map.entry("interaction_title", "<title>"),
                Map.entry("interaction_message", "<message>"),
                Map.entry("interaction_confirm_buttons", "<accept> <reject>"),
                Map.entry("interaction_accept_button", "accept"),
                Map.entry("interaction_reject_button", "reject"),
                Map.entry("interaction_accept_hover", "accept"),
                Map.entry("interaction_reject_hover", "reject"),
                Map.entry("interaction_select_option", "<label>"),
                Map.entry("interaction_select_option_hover", "<label>"),
                Map.entry("interaction_select_option_separator", " "));
        MessageService service = new MessageService(directory, defaults,
                () -> Files.writeString(directory.resolve("message.yml"), """
                        interaction_confirm_layout: |-
                          <separator>
                          <prefix><title>
                          <prefix><message>
                          <prefix><buttons>
                          <separator>
                        interaction_select_layout: |-
                          <separator>
                          <prefix><title>
                          <prefix><message>
                          <prefix><options>
                          <prefix><reject>
                          <separator>
                        interaction_prefix: '┃ '
                        interaction_separator: '===='
                        interaction_title: '<title>'
                        interaction_message: '<message>'
                        interaction_confirm_buttons: '<accept> │ <reject>'
                        interaction_accept_button: '[ Accept ]'
                        interaction_reject_button: '[ Reject ]'
                        interaction_accept_hover: 'accept'
                        interaction_reject_hover: 'reject'
                        interaction_select_option: '{<label>}'
                        interaction_select_option_hover: 'choose <label>'
                        interaction_select_option_separator: ' │ '
                        """, StandardCharsets.UTF_8), ignored -> { });
        service.seed();
        return service;
    }

    private static List<ClickEvent<?>> clicks(Component root) {
        ArrayList<ClickEvent<?>> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Component component, List<ClickEvent<?>> target) {
        if (component.clickEvent() != null) {
            target.add(component.clickEvent());
        }
        component.children().forEach(child -> collect(child, target));
    }

    private static String textPayload(ClickEvent<?> click) {
        return ((ClickEvent.Payload.Text) click.payload()).value();
    }
}
