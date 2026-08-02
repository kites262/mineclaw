package cc.kites.mineclaw.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSessionTest {
    @Test
    void retainsOnlyNewestConfiguredMessages() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("one", "a", 3);
        session.appendCompletedTurn("two", "b", 3);

        assertThat(session.snapshot()).extracting(PublicSession.Message::content)
                .containsExactly("two", "b");
    }

    @Test
    void tokenFallbackUsesDocumentedFourCharactersPerTokenApproximation() {
        int tokens = PublicSession.approximateTokens(
                java.util.List.of(new PublicSession.Message("user", "1234")), "5678");
        assertThat(tokens).isGreaterThanOrEqualTo(2);
    }

    @Test
    void snapshotAppliesANewLowerLimitWithoutWaitingForAnotherAppend() {
        PublicSession session = new PublicSession();
        session.appendCompletedTurn("one", "a", 10);
        session.appendCompletedTurn("two", "b", 10);

        assertThat(session.snapshot(2)).extracting(PublicSession.Message::content)
                .containsExactly("two", "b");
    }
}
