package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ApprovalPromptTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String TOKEN = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    void buildsTokenBoundAcceptAndRejectControlsWithoutOwningTheirLayout() {
        ApprovalPrompt.Controls controls = ApprovalPrompt.controls(
                TOKEN,
                Component.text("[接受]"),
                Component.text("[拒绝]"),
                Component.text("点击接受并提交此命令"),
                Component.text("点击拒绝；此命令不会提交"));

        List<Component> clickable = List.of(controls.accept(), controls.reject());
        assertThat(clickable).extracting(PLAIN::serialize).containsExactly("[接受]", "[拒绝]");
        assertCommand(clickable.get(0), "/mineclaw approve " + TOKEN);
        assertCommand(clickable.get(1), "/mineclaw reject " + TOKEN);
        assertThat(clickable).allSatisfy(component -> assertThat(component.hoverEvent()).isNotNull());
        assertThat(hoverText(clickable.get(0))).isEqualTo("点击接受并提交此命令");
    }

    @Test
    void rejectsAnythingExceptACanonicalLowercaseUuidToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> ApprovalPrompt.controls(
                "NOT-A-TOKEN",
                Component.empty(), Component.empty(), Component.empty(), Component.empty()));
    }

    private static void assertCommand(Component component, String expected) {
        ClickEvent<?> event = component.clickEvent();
        assertThat(event).isNotNull();
        assertThat(event.action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
        assertThat(event.payload()).isInstanceOfSatisfying(ClickEvent.Payload.Text.class,
                payload -> assertThat(payload.value()).isEqualTo(expected));
    }

    private static String hoverText(Component component) {
        HoverEvent<?> event = component.hoverEvent();
        assertThat(event).isNotNull();
        assertThat(event.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
        assertThat(event.value()).isInstanceOf(Component.class);
        return PLAIN.serialize((Component) event.value());
    }

}
