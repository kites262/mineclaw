package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/** Builds token-bound approval controls while leaving visual layout to message.yml. */
final class ApprovalPrompt {
    private static final Pattern TOKEN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ApprovalPrompt() { }

    static Controls controls(
            String token,
            Component acceptLabel,
            Component rejectLabel,
            Component acceptHover,
            Component rejectHover
    ) {
        Objects.requireNonNull(token, "token");
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("approval token must be a canonical lowercase UUID");
        }
        return new Controls(
                action(acceptLabel, acceptHover, "/mineclaw approve " + token),
                action(rejectLabel, rejectHover, "/mineclaw reject " + token));
    }

    private static Component action(Component label, Component hover, String command) {
        return Objects.requireNonNull(label, "label")
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Objects.requireNonNull(hover, "hover")));
    }

    record Controls(Component accept, Component reject) {
        Controls {
            Objects.requireNonNull(accept, "accept");
            Objects.requireNonNull(reject, "reject");
        }
    }
}
