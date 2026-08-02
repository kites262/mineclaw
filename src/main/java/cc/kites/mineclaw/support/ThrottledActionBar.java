package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Coalesces streaming deltas so token cadence cannot flood the entity scheduler. */
public final class ThrottledActionBar implements AutoCloseable {
    private static final long INTERVAL_MILLIS = 75L;

    private final Output output;
    private final Delay delay;
    private final SafeMarkdown.StreamingTail currentLine;
    private boolean previousWasCarriageReturn;
    private BreakState breakState = BreakState.NONE;
    private boolean paragraphHasInput;
    private boolean clearedSinceLastShow;
    private boolean dirty;
    private boolean scheduled;
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

    /** Adds one streamed delta, treating one logical line break as soft and two or more as a paragraph boundary. */
    public synchronized void append(String delta) {
        Objects.requireNonNull(delta, "delta");
        if (closed || delta.isEmpty()) {
            return;
        }
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
        if (dirty) {
            schedule();
        }
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            try {
                delay.schedule(this::flush);
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
            dirty = false;
            output.show(currentLine.render());
            clearedSinceLastShow = false;
        } else {
            dirty = false;
        }
    }

    /** Clears a failed streaming attempt before a retry starts. */
    public synchronized void reset() {
        if (!closed) {
            currentLine.clear();
            dirty = false;
            previousWasCarriageReturn = false;
            breakState = BreakState.NONE;
            paragraphHasInput = false;
            output.clear();
            clearedSinceLastShow = true;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            currentLine.clear();
            dirty = false;
            previousWasCarriageReturn = false;
            breakState = BreakState.NONE;
            paragraphHasInput = false;
            output.clear();
            clearedSinceLastShow = true;
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
        boolean hadContent = !currentLine.isEmpty();
        currentLine.clearLine();
        paragraphHasInput = false;
        dirty = false;
        if (hadContent && !clearedSinceLastShow) {
            output.clear();
            clearedSinceLastShow = true;
        }
    }

    private static Delay foliaDelay(FoliaTasks tasks) {
        Objects.requireNonNull(tasks, "tasks");
        return action -> tasks.asyncLater(INTERVAL_MILLIS, TimeUnit.MILLISECONDS, ignored -> action.run());
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
        void schedule(Runnable action);
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
