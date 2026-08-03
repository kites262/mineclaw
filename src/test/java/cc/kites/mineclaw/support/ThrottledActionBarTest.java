package cc.kites.mineclaw.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottledActionBarTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void coalescesDeltasAndDisplaysOnlyTheCurrentParagraph() {
        Harness harness = new Harness(120);

        harness.bar.append("first\n\nsecond\n\n**third");
        harness.bar.append(" line**");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("third");

        harness.flushOne();

        Component shown = harness.shown.getLast();
        assertThat(PLAIN.serialize(shown)).isEqualTo("third line");
        assertThat(PLAIN.serialize(shown)).doesNotContain("\n", "\r", "first", "second");
        assertThat(shown.children()).singleElement().satisfies(child ->
                assertThat(child.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE));
    }

    @Test
    void secondLfDefersReplacementAndCancelsQueuedStaleText() {
        Harness harness = new Harness(120);

        harness.bar.append("a");
        harness.bar.append("\n");

        assertThat(harness.clears).isZero();
        harness.bar.append("\n");

        assertThat(harness.clears).isZero();
        assertThat(harness.shown).isEmpty();
        harness.flushOne();
        assertThat(harness.shown).isEmpty();

        harness.bar.append("b");
        assertThat(harness.plainMessages()).containsExactly("b");
    }

    @Test
    void turnsOneLineBreakIntoASpaceWithoutClearing() {
        Harness harness = new Harness(120);

        harness.bar.append("a\n");

        assertThat(harness.clears).isZero();
        harness.flushOne();
        assertThat(harness.plainMessages()).containsExactly("a");

        harness.bar.append("b");
        harness.flushOne();

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("a", "a b");
    }

    @Test
    void secondBreakStillStartsANewParagraphAfterThePendingBreakWasFlushed() {
        Harness harness = new Harness(120);

        harness.bar.append("a\n");
        harness.flushOne();

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("a");

        harness.bar.append("\nb");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("a", "b");
    }

    @Test
    void treatsCrLfAcrossDeltasAsOneSoftBreak() {
        Harness harness = new Harness(120);

        harness.bar.append("a\r");
        harness.bar.append("\nb");
        harness.flushOne();

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("a b");
    }

    @Test
    void recognizesTwoCrLfBreaksSplitAcrossDeltasAsOneParagraphBoundary() {
        Harness harness = new Harness(120);

        harness.bar.append("a");
        harness.flushOne();
        harness.bar.append("\r");
        harness.bar.append("\n\r");
        harness.bar.append("\nb");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("a", "b");
    }

    @Test
    void newlineMeaningDoesNotDependOnAnySingleStreamingSplitPoint() {
        String softBreak = "old\r\nnew";
        for (int split = 0; split <= softBreak.length(); split++) {
            Harness harness = appendAtSplit(softBreak, split);

            assertThat(harness.clears).as("soft split %s", split).isZero();
            assertThat(harness.plainMessages()).as("soft split %s", split)
                    .containsExactly("old new");
        }

        String paragraphBreak = "old\r\n\r\nnew";
        for (int split = 0; split <= paragraphBreak.length(); split++) {
            Harness harness = appendAtSplit(paragraphBreak, split);

            assertThat(harness.clears).as("paragraph split %s", split).isZero();
            assertThat(harness.plainMessages().getLast()).as("paragraph split %s", split)
                    .isEqualTo("new");
            assertThat(harness.plainMessages()).as("paragraph split %s", split)
                    .noneMatch(message -> message.contains("old"));
        }
    }

    @Test
    void fourConsecutiveBreaksProduceOneParagraphWithoutLeadingSpace() {
        Harness harness = new Harness(120);

        harness.bar.append("old");
        harness.flushOne();
        harness.bar.append("\n\n\n\n");
        harness.bar.append("new");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("old", "new");
    }

    @Test
    void mixedLfAndCrLfFormOneParagraphBoundary() {
        Harness harness = new Harness(120);

        harness.bar.append("old");
        harness.flushOne();
        harness.bar.append("\n\r\nnew");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("old", "new");
    }

    @Test
    void softBreakFlushesAPendingMarkdownMarkerBeforeTheSpace() {
        Harness harness = new Harness(120);

        harness.bar.append("*");
        harness.bar.append("\ntext");
        harness.flushOne();

        assertThat(harness.plainMessages()).containsExactly("* text");
    }

    @Test
    void softBreakPreventsASurrogatePairFromSpanningTheBreak() {
        Harness harness = new Harness(120);

        harness.bar.append("\uD83D");
        harness.bar.append("\nX");
        harness.flushOne();

        assertThat(harness.plainMessages()).containsExactly("\uFFFD X");
    }

    @Test
    void recognizesBoldMarkersSplitAcrossStreamingDeltas() {
        Harness harness = new Harness(120);

        harness.bar.append("*");
        harness.bar.append("*bo");
        harness.flushOne();

        Component opening = harness.shown.getFirst();
        assertThat(PLAIN.serialize(opening)).isEqualTo("bo");
        assertThat(opening.children()).singleElement().satisfies(child ->
                assertThat(child.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE));

        harness.bar.append("ld*");
        harness.flushOne();

        Component halfClosing = harness.shown.get(1);
        assertThat(PLAIN.serialize(halfClosing)).isEqualTo("bold");
        assertThat(halfClosing.children()).singleElement().satisfies(child ->
                assertThat(child.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE));

        harness.bar.append("*");
        harness.bar.append(" done");
        harness.flushOne();

        Component closed = harness.shown.getLast();
        assertThat(PLAIN.serialize(closed)).isEqualTo("bold done");
        assertThat(closed.children()).hasSize(2);
        assertThat(closed.children().get(0).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(closed.children().get(1).decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.NOT_SET);
    }

    @Test
    void rendersNamedHexAndColorTagsAcrossEveryStreamingSplitPoint() {
        String source = "<red>named</red> <#12abef>hex</#12abef> "
                + "<color:green>green</color>";
        for (int split = 0; split <= source.length(); split++) {
            Harness harness = appendAtSplit(source, split);
            Component shown = harness.shown.getLast();

            assertThat(PLAIN.serialize(shown)).as("split %s", split)
                    .isEqualTo("named hex green");
            assertThat(descendants(shown)).as("named split %s", split).anySatisfy(child ->
                    assertThat(child.color()).isEqualTo(TextColor.color(0xff5555)));
            assertThat(descendants(shown)).as("hex split %s", split).anySatisfy(child ->
                    assertThat(child.color()).isEqualTo(TextColor.color(0x12abef)));
            assertThat(descendants(shown)).as("parameter split %s", split).anySatisfy(child ->
                    assertThat(child.color()).isEqualTo(TextColor.color(0x55ff55)));
        }
    }

    @Test
    void colorTagsDoNotConsumeTheVisibleTailBudget() {
        Harness harness = new Harness(4);

        harness.bar.append("<red>12345</red>");
        harness.flushOne();

        Component shown = harness.shown.getLast();
        assertThat(PLAIN.serialize(shown)).isEqualTo("2345");
        assertThat(descendants(shown)).anySatisfy(child ->
                assertThat(child.color()).isEqualTo(TextColor.color(0xff5555)));
    }

    @Test
    void leavesNonColorMiniMessageTagsLiteralAndNonInteractive() {
        Harness harness = new Harness(120);

        harness.bar.append("<click:run_command:'/op someone'>click</click>");
        harness.flushOne();

        Component shown = harness.shown.getLast();
        assertThat(PLAIN.serialize(shown))
                .isEqualTo("<click:run_command:'/op someone'>click</click>");
        assertThat(descendants(shown)).allSatisfy(child -> assertThat(child.clickEvent()).isNull());
    }

    @Test
    void preservesAnOpenBoldSpanAcrossASoftBreakAndThenRendersTheNextParagraph() {
        Harness harness = new Harness(120);

        harness.bar.append("**foo");
        harness.flushOne();
        harness.bar.append("\nbar**");
        harness.flushOne();

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("foo", "foo bar");
        assertThat(harness.shown.getLast().children()).singleElement().satisfies(child ->
                assertThat(child.decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE));

        harness.bar.append("\n\nnext");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("foo", "foo bar", "next");
    }

    @Test
    void boundsLongLinesAndCoalescesPathologicalLineBreakRuns() {
        Harness harness = new Harness(8);

        harness.bar.append("x".repeat(100_000));
        assertThat(harness.delayed).hasSize(1);
        harness.flushOne();
        assertThat(harness.plainMessages()).containsExactly("xxxxxxxx");

        harness.bar.append("\n".repeat(100_000));
        assertThat(harness.clears).isZero();
        assertThat(harness.scheduled(75L)).isZero();
    }

    @Test
    void retryReplacementKeepsStaleFrameUntilFreshTextIsRenderable() {
        Harness harness = new Harness(120);

        harness.bar.append("stale");
        harness.flushOne();
        harness.bar.replaceOnNextContent();
        harness.bar.append("**");

        assertThat(harness.clears).isZero();
        assertThat(harness.plainMessages()).containsExactly("stale");

        harness.bar.append("fresh**");

        assertThat(harness.plainMessages()).containsExactly("stale", "fresh");
        assertThat(harness.shown.getLast().children().getFirst().decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);

        harness.bar.close();
        harness.bar.append("ignored");
        assertThat(harness.clears).isOne();
    }

    @Test
    void thinkingPlaceholderSurvivesFormattingAndIsAtomicallyReplacedByFirstVisibleText() {
        Harness harness = new Harness(120);

        harness.bar.showInitial(Component.text("Thinking..."));
        harness.bar.append("\r\n**");

        assertThat(harness.plainMessages()).containsExactly("Thinking...");
        assertThat(harness.clears).isZero();

        harness.keepAlive();
        assertThat(harness.plainMessages()).containsExactly("Thinking...", "Thinking...");

        harness.bar.append("fresh**");

        assertThat(harness.plainMessages()).containsExactly("Thinking...", "Thinking...", "fresh");
        assertThat(harness.shown.getLast().children().getFirst().decoration(TextDecoration.BOLD))
                .isEqualTo(TextDecoration.State.TRUE);
        assertThat(harness.clears).isZero();
    }

    @Test
    void successfulFinishFlushesTheFinalFrameWithoutClearingAndStopsKeepAlive() {
        Harness harness = new Harness(120);

        harness.bar.showInitial(Component.text("Thinking..."));
        harness.bar.append("answer");
        harness.bar.append(" complete");
        harness.bar.finish();

        assertThat(harness.plainMessages()).containsExactly("Thinking...", "answer", "answer complete");
        assertThat(harness.clears).isZero();

        harness.keepAlive();
        assertThat(harness.plainMessages()).containsExactly("Thinking...", "answer", "answer complete");
    }

    @Test
    void cancellationClearsImmediatelyAndSuppressesQueuedUpdates() {
        Harness harness = new Harness(120);

        harness.bar.showInitial(Component.text("Thinking..."));
        harness.bar.append("queued");
        harness.bar.append(" update");
        harness.bar.close();
        harness.flushOne();
        harness.keepAlive();

        assertThat(harness.plainMessages()).containsExactly("Thinking...", "queued");
        assertThat(harness.clears).isOne();
    }

    private static Harness appendAtSplit(String source, int split) {
        Harness harness = new Harness(120);
        harness.bar.append(source.substring(0, split));
        harness.bar.append(source.substring(split));
        if (harness.scheduled(75L) > 0) {
            harness.flushOne();
        }
        return harness;
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

    private static final class Harness implements ThrottledActionBar.Output, ThrottledActionBar.Delay {
        private final List<Component> shown = new ArrayList<>();
        private final Deque<Scheduled> delayed = new ArrayDeque<>();
        private final ThrottledActionBar bar;
        private int clears;

        private Harness(int maximumCodePoints) {
            bar = new ThrottledActionBar(this, this, maximumCodePoints);
        }

        @Override
        public void show(Component message) {
            shown.add(message);
        }

        @Override
        public void clear() {
            clears++;
        }

        @Override
        public void schedule(long delayMillis, Runnable action) {
            delayed.addLast(new Scheduled(delayMillis, action));
        }

        private void flushOne() {
            runDelay(75L);
        }

        private void keepAlive() {
            runDelay(1_000L);
        }

        private long scheduled(long delayMillis) {
            return delayed.stream().filter(task -> task.delayMillis() == delayMillis).count();
        }

        private void runDelay(long delayMillis) {
            Scheduled selected = delayed.stream()
                    .filter(task -> task.delayMillis() == delayMillis)
                    .findFirst()
                    .orElseThrow();
            delayed.remove(selected);
            selected.action().run();
        }

        private List<String> plainMessages() {
            return shown.stream().map(PLAIN::serialize).toList();
        }

        private record Scheduled(long delayMillis, Runnable action) {
        }
    }
}
