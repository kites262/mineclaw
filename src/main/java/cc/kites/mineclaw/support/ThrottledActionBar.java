package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Coalesces streaming deltas so token cadence cannot flood the entity scheduler. */
public final class ThrottledActionBar implements AutoCloseable {
    private static final long INTERVAL_MILLIS = 75L;
    private static final long KEEP_ALIVE_MILLIS = 1_000L;

    private final Output output;
    private final Delay delay;
    private final SafeMarkdown.StreamingTail currentLine;
    private boolean previousWasCarriageReturn;
    private BreakState breakState = BreakState.NONE;
    private boolean paragraphHasInput;
    private Component visible;
    private boolean replaceOnNextContent;
    private boolean dirty;
    private boolean scheduled;
    private boolean keepAliveScheduled;
    private boolean closed;

    public ThrottledActionBar(Player player, PlayerChannel channel, FoliaTasks tasks, int maximumCodePoints) {
        this(new PlayerOutput(player, channel), foliaDelay(tasks), maximumCodePoints);
    }

    ThrottledActionBar(Output output, Delay delay, int maximumCodePoints) {
        this.output = Objects.requireNonNull(output, "output");
        this.delay = Objects.requireNonNull(delay, "delay");
        if (maximumCodePoints < 1) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        this.currentLine = new SafeMarkdown.StreamingTail(maximumCodePoints);
    }

    /** Shows a stable first-request placeholder that the first streamed text will replace. */
    public synchronized void showInitial(Component message) {
        Objects.requireNonNull(message, "message");
        if (closed || visible != null) {
            return;
        }
        visible = message;
        replaceOnNextContent = true;
        output.show(message);
        scheduleKeepAlive();
    }

    /** Adds one streamed delta, treating one logical line break as soft and two or more as a paragraph boundary. */
    public synchronized void append(String delta) {
        Objects.requireNonNull(delta, "delta");
        if (closed || delta.isEmpty()) {
            return;
        }
        append(delta, true);
    }

    /** Flushes the current streamed response and keeps its final frame alive for the next request. */
    public synchronized void hold() {
        if (!closed && dirty && !currentLine.isEmpty()) {
            flushCurrent();
        }
    }

    /** Atomically replaces the visible frame from one fully received intermediate response. */
    public synchronized void replaceComplete(String content) {
        Objects.requireNonNull(content, "content");
        if (closed || content.isBlank()) {
            return;
        }
        prepareReplacement();
        append(content, false);
        if (dirty && !currentLine.isEmpty()) {
            flushCurrent();
        } else {
            dirty = false;
        }
    }

    /** Atomically replaces model text with a trusted, already-rendered system activity frame. */
    public synchronized void replaceActivity(Component message) {
        Objects.requireNonNull(message, "message");
        if (closed) {
            return;
        }
        prepareReplacement();
        visible = message;
        output.show(message);
        scheduleKeepAlive();
    }

    private void append(String delta, boolean streaming) {
        for (int index = 0; index < delta.length(); index++) {
            char value = delta.charAt(index);
            if (value == '\r') {
                lineBreak();
                previousWasCarriageReturn = true;
                continue;
            }
            if (value == '\n') {
                if (previousWasCarriageReturn) {
                    previousWasCarriageReturn = false;
                } else {
                    lineBreak();
                }
                continue;
            }
            previousWasCarriageReturn = false;
            if (breakState == BreakState.SOFT && paragraphHasInput) {
                dirty |= currentLine.append(' ');
            }
            breakState = BreakState.NONE;
            paragraphHasInput = true;
            dirty |= currentLine.append(value);
        }
        if (!streaming) {
            return;
        }
        if (dirty && replaceOnNextContent && !currentLine.isEmpty()) {
            flushCurrent();
        } else if (dirty) {
            schedule();
        }
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            try {
                delay.schedule(INTERVAL_MILLIS, this::flush);
            } catch (RuntimeException exception) {
                scheduled = false;
            }
        }
    }

    private synchronized void flush() {
        scheduled = false;
        if (closed) {
            return;
        }
        if (dirty && !currentLine.isEmpty()) {
            flushCurrent();
        } else {
            dirty = false;
        }
    }

    /** Starts a fresh parser segment while preserving the visible frame until replacement text exists. */
    public synchronized void replaceOnNextContent() {
        if (!closed) {
            prepareReplacement();
        }
    }

    /** Stops updates after a successful turn and lets the final frame fade naturally. */
    public synchronized void finish() {
        if (!closed) {
            if (dirty && !currentLine.isEmpty()) {
                Component finalFrame = currentLine.render();
                dirty = false;
                visible = finalFrame;
                output.show(finalFrame);
            }
            retire(false);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            retire(true);
        }
    }

    private void retire(boolean clear) {
        closed = true;
        currentLine.clear();
        visible = null;
        replaceOnNextContent = false;
        dirty = false;
        previousWasCarriageReturn = false;
        breakState = BreakState.NONE;
        paragraphHasInput = false;
        if (clear) {
            output.clear();
        }
    }

    private void lineBreak() {
        if (breakState == BreakState.NONE) {
            breakState = BreakState.SOFT;
        } else if (breakState == BreakState.SOFT) {
            breakState = BreakState.PARAGRAPH;
            paragraphBreak();
        }
    }

    private void paragraphBreak() {
        prepareReplacement();
    }

    private void prepareReplacement() {
        currentLine.clear();
        paragraphHasInput = false;
        previousWasCarriageReturn = false;
        breakState = BreakState.PARAGRAPH;
        dirty = false;
        replaceOnNextContent = true;
    }

    private void flushCurrent() {
        Component message = currentLine.render();
        dirty = false;
        visible = message;
        replaceOnNextContent = false;
        output.show(message);
        scheduleKeepAlive();
    }

    private void scheduleKeepAlive() {
        if (!closed && visible != null && !keepAliveScheduled) {
            keepAliveScheduled = true;
            try {
                delay.schedule(KEEP_ALIVE_MILLIS, this::keepAlive);
            } catch (RuntimeException exception) {
                keepAliveScheduled = false;
            }
        }
    }

    private synchronized void keepAlive() {
        keepAliveScheduled = false;
        if (!closed && visible != null) {
            output.show(visible);
            scheduleKeepAlive();
        }
    }

    private static Delay foliaDelay(FoliaTasks tasks) {
        Objects.requireNonNull(tasks, "tasks");
        return (delayMillis, action) -> tasks.asyncLater(
                delayMillis, TimeUnit.MILLISECONDS, ignored -> action.run());
    }

    private enum BreakState {
        NONE,
        SOFT,
        PARAGRAPH
    }

    interface Output {
        void show(Component message);

        void clear();
    }

    @FunctionalInterface
    interface Delay {
        void schedule(long delayMillis, Runnable action);
    }

    private record PlayerOutput(Player player, PlayerChannel channel) implements Output {
        private PlayerOutput {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(channel, "channel");
        }

        @Override
        public void show(Component message) {
            channel.actionBar(player, message);
        }

        @Override
        public void clear() {
            channel.clearActionBar(player);
        }
    }
}
