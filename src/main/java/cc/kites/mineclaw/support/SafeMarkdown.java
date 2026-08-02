package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Renders the deliberately small, injection-safe Markdown subset accepted from model output. */
public final class SafeMarkdown {
    private SafeMarkdown() { }

    /** Only complete {@code **bold**} spans are interpreted; every other character remains literal text. */
    public static Component render(String source) {
        return component(parse(Objects.requireNonNull(source, "source")));
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
        runs.add(new Run(text, bold));
    }

    private static Component component(List<Run> runs) {
        TextComponent.Builder result = Component.text();
        for (Run run : runs) {
            Component child = Component.text(run.text());
            if (run.bold()) {
                child = child.decorate(TextDecoration.BOLD);
            }
            result.append(child);
        }
        return result.build();
    }

    /** Incremental, bounded parser used by the streaming ActionBar. */
    static final class StreamingTail {
        private static final int REPLACEMENT_CODE_POINT = 0xFFFD;

        private final int maximumCodePoints;
        private final ArrayDeque<Glyph> glyphs;
        private boolean bold;
        private boolean pendingAsterisk;
        private char pendingHighSurrogate;

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
        }

        /** Starts a new visible line while preserving an open bold span across the line boundary. */
        void clearLine() {
            glyphs.clear();
            pendingAsterisk = false;
            pendingHighSurrogate = 0;
        }

        Component render() {
            ArrayList<Run> runs = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Boolean runBold = null;
            for (Glyph glyph : glyphs) {
                if (runBold != null && runBold != glyph.bold()) {
                    add(runs, text.toString(), runBold);
                    text.setLength(0);
                }
                runBold = glyph.bold();
                text.appendCodePoint(glyph.codePoint());
            }
            if (runBold != null) {
                add(runs, text.toString(), runBold);
            }
            return component(runs);
        }

        private boolean emit(int codePoint) {
            if (glyphs.size() == maximumCodePoints) {
                glyphs.removeFirst();
            }
            glyphs.addLast(new Glyph(codePoint, bold));
            return true;
        }
    }

    private record Run(String text, boolean bold) { }

    private record Glyph(int codePoint, boolean bold) { }
}
