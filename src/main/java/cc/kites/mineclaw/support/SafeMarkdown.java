package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Renders the deliberately small, injection-safe Markdown subset accepted from model output. */
public final class SafeMarkdown {
    private static final MiniMessage COLORS = MiniMessage.builder().tags(StandardTags.color()).build();
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private SafeMarkdown() { }

    /** Complete {@code **bold**} spans and MiniMessage color tags are interpreted. */
    public static Component render(String source) {
        Component colored = COLORS.deserialize(Objects.requireNonNull(source, "source"));
        return applyMarkdown(colored);
    }

    /**
     * Renders a streaming tail. An opening {@code **} is treated as bold before its closing marker arrives,
     * and one trailing {@code *} is withheld because it may be half of the next marker.
     */
    public static Component renderTail(String source, int maximumCodePoints) {
        StreamingTail tail = new StreamingTail(maximumCodePoints);
        tail.append(Objects.requireNonNull(source, "source"));
        return tail.render();
    }

    private static List<Run> parse(String source) {
        ArrayList<Run> runs = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int opening = source.indexOf("**", cursor);
            if (opening < 0) {
                add(runs, source.substring(cursor), false);
                break;
            }
            int closing = source.indexOf("**", opening + 2);
            if (closing < 0) {
                add(runs, source.substring(cursor), false);
                break;
            }
            add(runs, source.substring(cursor, opening), false);
            add(runs, source.substring(opening + 2, closing), true);
            cursor = closing + 2;
        }
        return List.copyOf(runs);
    }

    private static void add(List<Run> runs, String text, boolean bold) {
        if (text.isEmpty()) {
            return;
        }
        runs.add(new Run(text, bold, null));
    }

    private static Component component(List<Run> runs) {
        TextComponent.Builder result = Component.text();
        for (Run run : runs) {
            Component child = Component.text(run.text());
            if (run.bold()) {
                child = child.decorate(TextDecoration.BOLD);
            }
            if (run.color() != null) {
                child = child.color(run.color());
            }
            result.append(child);
        }
        return result.build();
    }

    private static Component applyMarkdown(Component component) {
        List<Component> children = component.children().stream()
                .map(SafeMarkdown::applyMarkdown)
                .toList();
        if (!(component instanceof TextComponent text)) {
            return component.children(children);
        }

        TextComponent.Builder result = Component.text().style(text.style());
        for (Run run : parse(text.content())) {
            Component child = Component.text(run.text());
            if (run.bold()) {
                child = child.decorate(TextDecoration.BOLD);
            }
            result.append(child);
        }
        children.forEach(result::append);
        return result.build();
    }

    /** Incremental, bounded parser used by the streaming ActionBar. */
    static final class StreamingTail {
        private static final int REPLACEMENT_CODE_POINT = 0xFFFD;
        private static final int MAX_TAG_CHARS = 128;
        private static final int MAX_COLOR_DEPTH = 64;

        private final int maximumCodePoints;
        private final ArrayDeque<Glyph> glyphs;
        private final ArrayDeque<ColorFrame> colors = new ArrayDeque<>();
        private boolean bold;
        private boolean pendingAsterisk;
        private char pendingHighSurrogate;
        private StringBuilder pendingTag;

        StreamingTail(int maximumCodePoints) {
            if (maximumCodePoints < 1) {
                throw new IllegalArgumentException("maximumCodePoints must be positive");
            }
            this.maximumCodePoints = maximumCodePoints;
            this.glyphs = new ArrayDeque<>(Math.min(maximumCodePoints, 256));
        }

        boolean append(String text) {
            Objects.requireNonNull(text, "text");
            boolean changed = false;
            for (int index = 0; index < text.length(); index++) {
                changed |= append(text.charAt(index));
            }
            return changed;
        }

        boolean append(char value) {
            boolean changed = false;
            if (pendingHighSurrogate != 0) {
                if (Character.isLowSurrogate(value)) {
                    changed |= emit(Character.toCodePoint(pendingHighSurrogate, value));
                    pendingHighSurrogate = 0;
                    return changed;
                }
                changed |= emit(REPLACEMENT_CODE_POINT);
                pendingHighSurrogate = 0;
            }

            if (pendingTag != null) {
                pendingTag.append(value);
                if (value == '>') {
                    String candidate = pendingTag.toString();
                    pendingTag = null;
                    if (!applyColorTag(candidate)) {
                        changed |= emitLiteral(candidate);
                    }
                } else if (pendingTag.length() > MAX_TAG_CHARS) {
                    String candidate = pendingTag.toString();
                    pendingTag = null;
                    changed |= emitLiteral(candidate);
                }
                return changed;
            }

            if (value == '<') {
                if (pendingAsterisk) {
                    changed |= emit('*');
                    pendingAsterisk = false;
                }
                pendingTag = new StringBuilder().append(value);
                return changed;
            }

            if (value == '*') {
                if (pendingAsterisk) {
                    pendingAsterisk = false;
                    bold = !bold;
                } else {
                    pendingAsterisk = true;
                }
                return changed;
            }
            if (pendingAsterisk) {
                changed |= emit('*');
                pendingAsterisk = false;
            }
            if (Character.isHighSurrogate(value)) {
                pendingHighSurrogate = value;
            } else if (Character.isLowSurrogate(value)) {
                changed |= emit(REPLACEMENT_CODE_POINT);
            } else {
                changed |= emit(value);
            }
            return changed;
        }

        boolean isEmpty() {
            return glyphs.isEmpty();
        }

        void clear() {
            glyphs.clear();
            bold = false;
            pendingAsterisk = false;
            pendingHighSurrogate = 0;
            pendingTag = null;
            colors.clear();
        }

        /** Starts a new visible line while preserving an open bold span across the line boundary. */
        void clearLine() {
            glyphs.clear();
            pendingAsterisk = false;
            pendingHighSurrogate = 0;
            pendingTag = null;
        }

        Component render() {
            ArrayList<Run> runs = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Boolean runBold = null;
            TextColor runColor = null;
            for (Glyph glyph : glyphs) {
                if (runBold != null && (runBold != glyph.bold()
                        || !Objects.equals(runColor, glyph.color()))) {
                    add(runs, text.toString(), runBold, runColor);
                    text.setLength(0);
                }
                runBold = glyph.bold();
                runColor = glyph.color();
                text.appendCodePoint(glyph.codePoint());
            }
            if (runBold != null) {
                add(runs, text.toString(), runBold, runColor);
            }
            return component(runs);
        }

        private boolean emit(int codePoint) {
            if (glyphs.size() == maximumCodePoints) {
                glyphs.removeFirst();
            }
            glyphs.addLast(new Glyph(codePoint, bold, currentColor()));
            return true;
        }

        private boolean emitLiteral(String source) {
            boolean changed = false;
            for (int codePoint : source.codePoints().toArray()) {
                changed |= emit(codePoint);
            }
            return changed;
        }

        private TextColor currentColor() {
            return colors.isEmpty() ? null : colors.getFirst().color();
        }

        private boolean applyColorTag(String candidate) {
            if (candidate.length() < 3 || candidate.charAt(0) != '<'
                    || candidate.charAt(candidate.length() - 1) != '>') {
                return false;
            }
            String body = candidate.substring(1, candidate.length() - 1);
            if (body.equals("/")) {
                if (!colors.isEmpty()) {
                    colors.removeFirst();
                }
                return true;
            }
            if (body.startsWith("/")) {
                String closing = body.substring(1).toLowerCase(Locale.ROOT);
                if (!colors.isEmpty() && colors.getFirst().closingTag().equals(closing)) {
                    colors.removeFirst();
                    return true;
                }
                return false;
            }
            ColorFrame frame = openingColor(body);
            if (frame == null || colors.size() >= MAX_COLOR_DEPTH) {
                return false;
            }
            colors.addFirst(frame);
            return true;
        }

        private static ColorFrame openingColor(String body) {
            String normalized = body.toLowerCase(Locale.ROOT);
            TextColor direct = color(normalized);
            if (direct != null) {
                return new ColorFrame(normalized, direct);
            }
            int separator = normalized.indexOf(':');
            if (separator < 1 || separator == normalized.length() - 1) {
                return null;
            }
            String tag = normalized.substring(0, separator);
            if (!tag.equals("color") && !tag.equals("c")) {
                return null;
            }
            TextColor parameter = color(normalized.substring(separator + 1));
            return parameter == null ? null : new ColorFrame(tag, parameter);
        }

        private static TextColor color(String value) {
            if (HEX_COLOR.matcher(value).matches()) {
                return TextColor.fromHexString(value);
            }
            return NamedTextColor.NAMES.value(value);
        }
    }

    private static void add(List<Run> runs, String text, boolean bold, TextColor color) {
        if (!text.isEmpty()) {
            runs.add(new Run(text, bold, color));
        }
    }

    private record Run(String text, boolean bold, TextColor color) { }

    private record Glyph(int codePoint, boolean bold, TextColor color) { }

    private record ColorFrame(String closingTag, TextColor color) { }
}
