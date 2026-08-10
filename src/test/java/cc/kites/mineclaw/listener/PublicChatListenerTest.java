package cc.kites.mineclaw.listener;

import cc.kites.mineclaw.config.MineclawConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PublicChatListenerTest {
    @Test
    void literalPrefixRequiresTokenBoundaryAndStripsIt() {
        MineclawConfig.Chat chat = new MineclawConfig.Chat("@ai", Optional.empty(), 2_000, 120);
        assertThat(PublicChatListener.parseWake("@ai hello", chat)).contains("hello");
        assertThat(PublicChatListener.parseWake("@AI\t你好", chat)).contains("你好");
        assertThat(PublicChatListener.parseWake("@air hello", chat)).isEmpty();
    }

    @Test
    void configuredRegexUsesFirstCaptureGroup() {
        MineclawConfig.Chat chat = new MineclawConfig.Chat("ignored",
                Optional.of(Pattern.compile("(?i)^bot[:,]\\s*(.*)$")), 2_000, 120);
        assertThat(PublicChatListener.parseWake("Bot: status", chat)).contains("status");
    }

    @Test
    void listenModeAcceptsTheWholeMessageWithoutAWakePrefix() {
        MineclawConfig.Chat chat = new MineclawConfig.Chat("@ai", Optional.empty(), 2_000, 120);

        assertThat(PublicChatListener.parseInput("hello everyone", chat, true))
                .contains("hello everyone");
        assertThat(PublicChatListener.parseInput("hello everyone", chat, false)).isEmpty();
        assertThat(PublicChatListener.parseInput("@ai hello everyone", chat, true))
                .contains("hello everyone");
    }

    @Test
    void implicitListenMessagesReceiveAVisiblePublicPrefix() {
        Component displayed = PublicChatListener.addListenPrefix(
                Component.text("hello everyone"), "@ai");

        assertThat(PlainTextComponentSerializer.plainText().serialize(displayed))
                .isEqualTo("@ai hello everyone");
    }
}
