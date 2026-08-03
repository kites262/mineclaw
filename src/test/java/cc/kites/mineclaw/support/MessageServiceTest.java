package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageServiceTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String TOKEN = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    void seedsAndHotReadsAnOrdinaryMessageFile(@TempDir Path directory) throws Exception {
        ArrayList<String> warnings = new ArrayList<>();
        MessageService service = service(directory, warnings);

        service.seed();

        assertThat(service.readTemplate("sample")).isEqualTo("live");
        assertThat(warnings).isEmpty();
    }

    @Test
    void seedRejectsAHardLinkToAProtectedFileWithoutEchoingIt(@TempDir Path directory)
            throws Exception {
        Path secret = directory.resolve(".env");
        Files.writeString(secret, "TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createLink(directory.resolve("message.yml"), secret);
        MessageService service = service(directory, new ArrayList<>());

        assertThatThrownBy(service::seed)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("TOP_SECRET_VALUE");
    }

    @Test
    void unsafeRuntimeReplacementFallsBackWithoutReadingOrLoggingContent(@TempDir Path directory)
            throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path outside = directory.resolve("outside.yml");
        Files.writeString(outside, "sample: TOP_SECRET_VALUE", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve("message.yml"), outside);
        ArrayList<String> warnings = new ArrayList<>();
        MessageService service = service(workspace, warnings);

        assertThat(service.readTemplate("sample")).isEqualTo("fallback");
        assertThat(warnings).singleElement().asString()
                .doesNotContain("TOP_SECRET_VALUE", outside.toString());
    }

    @Test
    void rendersAllMiniMessageNamedAndHexColorTags(@TempDir Path directory) {
        Map<String, TextColor> expected = new LinkedHashMap<>();
        expected.put("black", NamedTextColor.BLACK);
        expected.put("dark_blue", NamedTextColor.DARK_BLUE);
        expected.put("dark_green", NamedTextColor.DARK_GREEN);
        expected.put("dark_aqua", NamedTextColor.DARK_AQUA);
        expected.put("dark_red", NamedTextColor.DARK_RED);
        expected.put("dark_purple", NamedTextColor.DARK_PURPLE);
        expected.put("gold", NamedTextColor.GOLD);
        expected.put("gray", NamedTextColor.GRAY);
        expected.put("dark_gray", NamedTextColor.DARK_GRAY);
        expected.put("blue", NamedTextColor.BLUE);
        expected.put("green", NamedTextColor.GREEN);
        expected.put("aqua", NamedTextColor.AQUA);
        expected.put("red", NamedTextColor.RED);
        expected.put("light_purple", NamedTextColor.LIGHT_PURPLE);
        expected.put("yellow", NamedTextColor.YELLOW);
        expected.put("white", NamedTextColor.WHITE);
        String namedTags = expected.keySet().stream()
                .map(name -> "<" + name + ">" + name + "</" + name + ">")
                .reduce("", String::concat);
        String template = namedTags + "<#123456>hex</#123456>"
                + "<color:#abcdef>explicit_hex</color>";
        MessageService service = serviceWithDefault(directory, template);

        Map<String, TextColor> actual = new LinkedHashMap<>();
        collectTextColors(service.render("sample"), null, actual);

        assertThat(actual).containsAllEntriesOf(expected);
        assertThat(actual).containsEntry("hex", TextColor.color(0x123456));
        assertThat(actual).containsEntry("explicit_hex", TextColor.color(0xabcdef));
    }

    @Test
    void doesNotRenderAnyOtherMiniMessageStyleTags(@TempDir Path directory) {
        String template = "<bold>bold</bold>"
                + "<gradient:red:blue>gradient</gradient><rainbow>rainbow</rainbow>"
                + "<reset>reset</reset><click:run_command:/op>Alice</click>";
        Component rendered = serviceWithDefault(directory, template).render("sample");

        assertThat(hasDecoration(rendered, TextDecoration.BOLD)).isFalse();
        assertThat(clicks(rendered)).isEmpty();
        assertThat(PLAIN.serialize(rendered)).contains(
                "<bold>", "<gradient:red:blue>", "<rainbow>", "<reset>",
                "<click:run_command:/op>");
    }

    @Test
    void approvalLayoutControlsStructureWhileButtonsKeepFixedTokenActions(@TempDir Path directory)
            throws Exception {
        Map<String, String> templates = approvalTemplates();
        MessageService service = new MessageService(directory, templates,
                () -> Files.writeString(directory.resolve("message.yml"), """
                        approve_layout: |-
                          <separator>
                          <prefix><title>
                          <prefix><requester>
                          <prefix><intent>
                          <prefix><command>
                          <prefix><player>
                          <prefix><expires>
                          <prefix><buttons>
                          <separator>
                        approve_prefix: '┃ '
                        approve_separator: '━━━━━━━━'
                        approve_buttons: '<accept> │ <reject>'
                        approve_title: '命令执行确认'
                        approve_requester: '请求来自：<requester>'
                        approve_intent: '操作内容：<intent>'
                        approve_command: '命令：/<command>'
                        approve_player: '执行身份：<player>'
                        approve_expires: '60 秒内确认'
                        approve_accept_button: '「接受」'
                        approve_reject_button: '「拒绝」'
                        approve_accept_hover: '点击接受'
                        approve_reject_hover: '点击拒绝'
                        """, StandardCharsets.UTF_8), ignored -> { });
        service.seed();

        Component prompt = service.renderApprovalPrompt(Map.of(
                "token", TOKEN,
                "requester", "Alice",
                "intent", "定位末地城",
                "command", "locate structure end_city",
                "player", "Bob"));

        assertThat(PLAIN.serialize(prompt)).isEqualTo("""
                ━━━━━━━━
                ┃ 命令执行确认
                ┃ 请求来自：Alice
                ┃ 操作内容：定位末地城
                ┃ 命令：/locate structure end_city
                ┃ 执行身份：Bob
                ┃ 60 秒内确认
                ┃ 「接受」 │ 「拒绝」
                ━━━━━━━━""");
        List<ClickEvent<?>> clicks = clicks(prompt);
        assertThat(clicks).extracting(MessageServiceTest::textPayload).containsExactly(
                "/mineclaw approve " + TOKEN,
                "/mineclaw reject " + TOKEN);
    }

    @Test
    void messageTemplatesCannotInstallAdditionalClickActions(@TempDir Path directory) throws Exception {
        Map<String, String> templates = approvalTemplates();
        MessageService service = new MessageService(directory, templates,
                () -> Files.writeString(directory.resolve("message.yml"),
                        "approve_layout: '<click:run_command:/op Alice><buttons></click>'\n"
                                + "approve_buttons: '<accept> <reject>'\n",
                        StandardCharsets.UTF_8), ignored -> { });
        service.seed();

        Component prompt = service.renderApprovalPrompt(Map.of(
                "token", TOKEN, "requester", "Alice", "intent", "test",
                "command", "help", "player", "Alice"));

        assertThat(clicks(prompt)).extracting(MessageServiceTest::textPayload).containsExactly(
                "/mineclaw approve " + TOKEN,
                "/mineclaw reject " + TOKEN);
    }

    private static Map<String, String> approvalTemplates() {
        return Map.ofEntries(
                Map.entry("approve_layout", "<buttons>"),
                Map.entry("approve_prefix", ""),
                Map.entry("approve_separator", ""),
                Map.entry("approve_buttons", "<accept> <reject>"),
                Map.entry("approve_title", "title"),
                Map.entry("approve_requester", "<requester>"),
                Map.entry("approve_intent", "<intent>"),
                Map.entry("approve_command", "<command>"),
                Map.entry("approve_player", "<player>"),
                Map.entry("approve_expires", "expires"),
                Map.entry("approve_accept_button", "accept"),
                Map.entry("approve_reject_button", "reject"),
                Map.entry("approve_accept_hover", "accept hover"),
                Map.entry("approve_reject_hover", "reject hover"));
    }

    private static List<ClickEvent<?>> clicks(Component root) {
        ArrayList<ClickEvent<?>> result = new ArrayList<>();
        collectClicks(root, result);
        return result;
    }

    private static void collectTextColors(Component component, TextColor inherited,
                                          Map<String, TextColor> target) {
        TextColor effective = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            target.put(text.content(), effective);
        }
        component.children().forEach(child -> collectTextColors(child, effective, target));
    }

    private static boolean hasDecoration(Component component, TextDecoration decoration) {
        if (component.decoration(decoration) == TextDecoration.State.TRUE) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasDecoration(child, decoration));
    }

    private static void collectClicks(Component component, List<ClickEvent<?>> target) {
        if (component.clickEvent() != null) {
            target.add(component.clickEvent());
        }
        component.children().forEach(child -> collectClicks(child, target));
    }

    private static String textPayload(ClickEvent<?> click) {
        return ((ClickEvent.Payload.Text) click.payload()).value();
    }

    private static MessageService service(Path directory, ArrayList<String> warnings) {
        return new MessageService(directory, Map.of("sample", "fallback"),
                () -> Files.writeString(directory.resolve("message.yml"), "sample: live\n",
                        StandardCharsets.UTF_8),
                warnings::add);
    }

    private static MessageService serviceWithDefault(Path directory, String template) {
        return new MessageService(directory, Map.of("sample", template), () -> { }, ignored -> { });
    }
}
