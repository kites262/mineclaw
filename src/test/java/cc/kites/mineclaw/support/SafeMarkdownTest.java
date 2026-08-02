package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafeMarkdownTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void rendersOnlyCompleteDoubleAsteriskSpansAsBold() {
        Component rendered = SafeMarkdown.render("plain **bold** end **unfinished");

        assertThat(PLAIN.serialize(rendered)).isEqualTo("plain bold end **unfinished");
        assertThat(rendered.children()).hasSize(3);
        assertThat(rendered.children().get(0).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.NOT_SET);
        assertThat(rendered.children().get(1).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(rendered.children().get(2).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.NOT_SET);
    }

    @Test
    void keepsMiniMessageLookingModelTextLiteralAndNonInteractive() {
        Component safeReply = SafeMarkdown.render(
                "<red>literal</red> **safe** <click:run_command:'/op someone'>click</click>");
        Component rendered = MiniMessage.miniMessage().deserialize("<reply>",
                Placeholder.component("reply", safeReply));

        assertThat(PLAIN.serialize(rendered)).isEqualTo(
                "<red>literal</red> safe <click:run_command:'/op someone'>click</click>");
        assertThat(descendants(rendered)).allSatisfy(child -> {
            assertThat(child.clickEvent()).isNull();
            assertThat(child.color()).isNull();
        });
    }

    @Test
    void tailLimitCountsVisibleCodePointsAndPreservesBoldDecoration() {
        Component rendered = SafeMarkdown.renderTail("prefix **bold** tail", 9);

        assertThat(PLAIN.serialize(rendered)).isEqualTo("bold tail");
        assertThat(rendered.children()).hasSize(2);
        assertThat(rendered.children().get(0).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(rendered.children().get(1).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.NOT_SET);
    }

    @Test
    void streamingTailHidesPendingMarkersAndRendersAnOpenBoldSpan() {
        Component opening = SafeMarkdown.renderTail("plain **bo", 120);
        Component halfClosing = SafeMarkdown.renderTail("plain **bold*", 120);

        assertThat(PLAIN.serialize(opening)).isEqualTo("plain bo");
        assertThat(opening.children().get(1).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(PLAIN.serialize(halfClosing)).isEqualTo("plain bold");
        assertThat(halfClosing.children().get(1).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(PLAIN.serialize(SafeMarkdown.renderTail("pending*", 120))).isEqualTo("pending");

        // Final chat stays strict so malformed Markdown is never silently rewritten.
        assertThat(PLAIN.serialize(SafeMarkdown.render("plain **bo"))).isEqualTo("plain **bo");
    }

    private static List<Component> descendants(Component root) {
        ArrayList<Component> result = new ArrayList<>();
        append(root, result);
        return result;
    }

    private static void append(Component value, List<Component> result) {
        result.add(value);
        value.children().forEach(child -> append(child, result));
    }
}
