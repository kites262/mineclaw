package cc.kites.mineclaw.session;

import cc.kites.mineclaw.api.ApiMessage;
import cc.kites.mineclaw.api.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSessionTest {
    @Test
    void playerIdentityMarkerIsGeneratedWithoutChangingStoredContent() {
        ApiMessage message = ApiMessage.user("Alice", "where is my base?");

        assertThat(message.content()).isEqualTo("where is my base?");
        assertThat(message.modelContent()).isEqualTo("<player>Alice</player>\nwhere is my base?");
    }

    @Test
    void promptCacheKeyIsStableForASessionAndRotatesOnClear() {
        PublicSession session = new PublicSession();
        String initial = session.snapshotState(24).promptCacheKey();

        session.appendCompletedTurn("Alice", "one", "answer", 24);
        assertThat(session.snapshotState(24).promptCacheKey()).isEqualTo(initial);
        assertThat(initial).matches("mineclaw:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                + "[0-9a-f]{4}-[0-9a-f]{12}");

        session.clear();

        assertThat(session.snapshotState(24).promptCacheKey()).isNotEqualTo(initial);
    }

    @Test
    void retainsOnlyNewestConfiguredMessages() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("Alice", "one", "a", 3);
        session.appendCompletedTurn("Bob", "two", "b", 3);

        assertThat(session.snapshot()).extracting(ApiMessage::content)
                .containsExactly("two", "b");
        assertThat(session.snapshot()).extracting(ApiMessage::name)
                .containsExactly("Bob", null);
    }

    @Test
    void failedTurnRetainsToolTranscriptAndClosesInterruptedCalls() {
        PublicSession session = new PublicSession();
        ToolCall completed = new ToolCall("call-1", "read", "{\"path\":\"notes.md\"}");
        ToolCall interrupted = new ToolCall("call-2", "run_command", "{\"command\":\"say hi\"}");

        session.appendFailedTurn(List.of(
                ApiMessage.user("Alice", "continue the operation"),
                ApiMessage.assistantToolCalls(List.of(completed, interrupted)),
                ApiMessage.tool("call-1", "{\"status\":\"ok\",\"output\":{\"content\":\"saved detail\"}}")
        ), "[Turn timed out]", 24);

        assertThat(session.snapshot()).extracting(ApiMessage::role)
                .containsExactly("user", "assistant", "tool", "tool", "assistant");
        assertThat(session.snapshot().get(2).content()).contains("saved detail");
        assertThat(session.snapshot().getFirst().name()).isEqualTo("Alice");
        assertThat(session.snapshot().get(3).toolCallId()).isEqualTo("call-2");
        assertThat(session.snapshot().get(3).content())
                .contains("turn_interrupted", "Tool call did not complete");
        assertThat(session.snapshot().getLast().content()).isEqualTo("[Turn timed out]");
    }

    @Test
    void snapshotAppliesANewLowerLimitWithoutWaitingForAnotherAppend() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("Alice", "one", "a", 10);
        session.appendCompletedTurn("Bob", "two", "b", 10);

        assertThat(session.snapshot(2)).extracting(ApiMessage::content)
                .containsExactly("two", "b");
    }

    @Test
    void messageLimitNeverSplitsTheNewestFailedTurn() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("Alice", "old", "answer", 24);
        session.appendFailedTurn(List.of(ApiMessage.user("Bob", "new")), "[failed]", 1);

        assertThat(session.snapshot()).extracting(ApiMessage::content)
                .containsExactly("new", "[failed]");
    }

    @Test
    void compactionPublishesSummaryAndRetainedWholeTurnsAtomically() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("Alice", "old", "old answer", 24);
        session.appendCompletedTurn("Bob", "recent", "recent answer", 24);
        PublicSession.Snapshot source = session.snapshotState(24);

        PublicSession.Snapshot compacted = session.publishCompaction(source.revision(),
                "goal: continue safely", List.of(source.turns().getLast()), 24).orElseThrow();

        assertThat(compacted.summary()).isEqualTo("goal: continue safely");
        assertThat(compacted.messages()).extracting(ApiMessage::content)
                .containsExactly("recent", "recent answer");
        assertThat(compacted.messages().getFirst().name()).isEqualTo("Bob");
        assertThat(session.snapshotState(24)).isEqualTo(compacted);
    }

    @Test
    void staleCompactionCannotResurrectClearedHistory() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("Alice", "old", "answer", 24);
        PublicSession.Snapshot source = session.snapshotState(24);

        session.clear();

        assertThat(session.publishCompaction(source.revision(), "stale summary", source.turns(), 24))
                .isEmpty();
        assertThat(session.snapshotState(24).summary()).isEmpty();
        assertThat(session.snapshot()).isEmpty();
    }
}
