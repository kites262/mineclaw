package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ApprovalPromptTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String TOKEN = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    void laysOutDetailsThenTokenBoundAcceptAndRejectButtonsOnTheLastLine() {
        Component prompt = ApprovalPrompt.render(
                TOKEN,
                Component.text("命令执行确认"),
                Component.text("请求来自：Alice"),
                Component.text("操作内容：定位最近的末地城"),
                Component.text("命令：/locate structure end_city"),
                Component.text("执行身份：Bob"),
                Component.text("请在 60 秒内确认"),
                Component.text("快捷接受：按住 Shift，视角朝向正上方，主手持有不会对空气产生效果的物品，再右键空气"),
                Component.text("[接受]"),
                Component.text("[拒绝]"),
                Component.text("点击接受并提交此命令"),
                Component.text("点击拒绝；此命令不会提交"));

        assertThat(PLAIN.serialize(prompt)).isEqualTo("""
                命令执行确认
                请求来自：Alice
                操作内容：定位最近的末地城
                命令：/locate structure end_city
                执行身份：Bob
                请在 60 秒内确认
                [接受]   [拒绝]""");

        List<Component> clickable = descendants(prompt).stream()
                .filter(component -> component.clickEvent() != null)
                .toList();
        assertThat(clickable).hasSize(2);
        assertCommand(clickable.get(0), "/mineclaw approve " + TOKEN);
        assertCommand(clickable.get(1), "/mineclaw reject " + TOKEN);
        assertThat(clickable).allSatisfy(component -> assertThat(component.hoverEvent()).isNotNull());
        assertThat(hoverText(clickable.get(0))).isEqualTo(
                "点击接受并提交此命令\n快捷接受：按住 Shift，视角朝向正上方，"
                        + "主手持有不会对空气产生效果的物品，再右键空气");
        assertThat(PLAIN.serialize(prompt)).doesNotContain("快捷接受");
    }

    @Test
    void rejectsAnythingExceptACanonicalLowercaseUuidToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> ApprovalPrompt.render(
                "NOT-A-TOKEN",
                Component.empty(), Component.empty(), Component.empty(), Component.empty(), Component.empty(),
                Component.empty(), Component.empty(), Component.empty(), Component.empty(), Component.empty(),
                Component.empty()));
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
