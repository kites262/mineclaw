package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/** Builds the fixed, injection-safe interactive portion of a command approval prompt. */
final class ApprovalPrompt {
    private static final Pattern TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ApprovalPrompt() { }

    static Component render(
            String token,
            Component title,
            Component requester,
            Component intent,
            Component command,
            Component player,
            Component expires,
            Component gesture,
            Component acceptLabel,
            Component rejectLabel,
            Component acceptHover,
            Component rejectHover
    ) {
        Objects.requireNonNull(token, "token");
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("approval token must be a canonical lowercase UUID");
        }
        Component acceptInstructions = Component.text()
                .append(Objects.requireNonNull(acceptHover, "acceptHover"))
                .append(Component.newline())
                .append(Objects.requireNonNull(gesture, "gesture"))
                .build();
        Component accept = action(acceptLabel, acceptInstructions, "/mineclaw approve " + token);
        Component reject = action(rejectLabel, rejectHover, "/mineclaw reject " + token);
        TextComponent.Builder message = Component.text();
        appendLine(message, title);
        appendLine(message, requester);
        appendLine(message, intent);
        appendLine(message, command);
        appendLine(message, player);
        appendLine(message, expires);
        return message.append(accept).append(Component.text("   ")).append(reject).build();
    }

    private static Component action(Component label, Component hover, String command) {
        return Objects.requireNonNull(label, "label")
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Objects.requireNonNull(hover, "hover")));
    }

    private static void appendLine(TextComponent.Builder target, Component line) {
        target.append(Objects.requireNonNull(line, "line")).append(Component.newline());
    }
}
