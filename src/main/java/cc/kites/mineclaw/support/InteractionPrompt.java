package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Builds fixed, token-bound controls for JavaScript confirm and select interactions. */
public final class InteractionPrompt {
    private static final Pattern OPTION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private InteractionPrompt() { }

    static Controls confirmControls(
            String token,
            Component acceptLabel,
            Component rejectLabel,
            Component acceptHover,
            Component rejectHover
    ) {
        requireToken(token);
        return new Controls(
                action(acceptLabel, acceptHover, "/mineclaw approve " + token),
                action(rejectLabel, rejectHover, "/mineclaw reject " + token));
    }

    static Component selectOption(String token, String optionId, Component label, Component hover) {
        requireToken(token);
        String safeId = Objects.requireNonNull(optionId, "optionId");
        if (!OPTION_ID.matcher(safeId).matches()) {
            throw new IllegalArgumentException("select option id must contain only safe identifier characters");
        }
        return action(label, hover, "/mineclaw select " + token + " " + safeId);
    }

    static Component reject(String token, Component label, Component hover) {
        requireToken(token);
        return action(label, hover, "/mineclaw reject " + token);
    }

    private static Component action(Component label, Component hover, String command) {
        return Objects.requireNonNull(label, "label")
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Objects.requireNonNull(hover, "hover")));
    }

    private static void requireToken(String token) {
        Objects.requireNonNull(token, "token");
        try {
            if (!UUID.fromString(token).toString().equals(token)) {
                throw new IllegalArgumentException("interaction token must be canonical UUID text");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("interaction token must be canonical UUID text", exception);
        }
    }

    record Controls(Component accept, Component reject) {
        Controls {
            Objects.requireNonNull(accept, "accept");
            Objects.requireNonNull(reject, "reject");
        }
    }
}
