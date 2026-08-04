package cc.kites.mineclaw.command;

import cc.kites.mineclaw.interaction.InteractionManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MineclawCommandTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String FIRST_TOKEN = "10000000-0000-4000-8000-000000000001";
    private static final String LATEST_TOKEN = "20000000-0000-4000-8000-000000000002";

    @Test
    void tokenlessApproveAlwaysCompletesTheLatestWaitingConfirm() {
        InteractionManager interactions = manager();
        InteractionManager.Registration first = interactions.request(confirm(FIRST_TOKEN));

        assertThat(interactions.reject(PLAYER, FIRST_TOKEN)).isEqualTo(InteractionManager.Outcome.REJECTED);
        assertThat(first.result().join().status()).isEqualTo(InteractionManager.Status.REJECTED);

        InteractionManager.Registration latest = interactions.request(confirm(LATEST_TOKEN));

        assertThat(MineclawCommand.completeDecision(
                interactions, PLAYER, new String[]{"approve"}, true))
                .isEqualTo(InteractionManager.Outcome.APPROVED);
        assertThat(latest.result().join())
                .extracting(InteractionManager.Result::status, InteractionManager.Result::value)
                .containsExactly(InteractionManager.Status.APPROVED, Boolean.TRUE);
        assertThat(interactions.approve(PLAYER, FIRST_TOKEN)).isEqualTo(InteractionManager.Outcome.NONE);
    }

    @Test
    void tokenlessApproveDoesNotChooseForASelectRequest() {
        InteractionManager interactions = manager();
        InteractionManager.Registration select = interactions.request(new InteractionManager.Request(
                PLAYER, "Player", LATEST_TOKEN, "scope", "select",
                new InteractionManager.Select("Choose", "Pick one", java.util.List.of(
                        new InteractionManager.Option("a", "A"),
                        new InteractionManager.Option("b", "B"))),
                Duration.ofSeconds(60), InteractionManager.CURRENT_GENERATION));

        assertThat(MineclawCommand.completeDecision(
                interactions, PLAYER, new String[]{"approve"}, true))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(select.result()).isNotDone();
        assertThat(interactions.select(PLAYER, LATEST_TOKEN, "b"))
                .isEqualTo(InteractionManager.Outcome.SELECTED);
    }

    @Test
    void explicitTokensRemainExactAndRejectStillRequiresOne() {
        InteractionManager interactions = manager();
        InteractionManager.Registration confirm = interactions.request(confirm(LATEST_TOKEN));

        assertThat(MineclawCommand.completeDecision(
                interactions, PLAYER, new String[]{"reject"}, false))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(MineclawCommand.completeDecision(
                interactions, PLAYER, new String[]{"approve", FIRST_TOKEN}, true))
                .isEqualTo(InteractionManager.Outcome.NONE);
        assertThat(confirm.result()).isNotDone();
        assertThat(MineclawCommand.completeDecision(
                interactions, PLAYER, new String[]{"approve", LATEST_TOKEN}, true))
                .isEqualTo(InteractionManager.Outcome.APPROVED);
    }

    private static InteractionManager manager() {
        return new InteractionManager((delay, action) -> () -> { });
    }

    private static InteractionManager.Request confirm(String token) {
        return new InteractionManager.Request(
                PLAYER, "Player", token, "scope-" + token, "confirm-" + token,
                new InteractionManager.Confirm("Confirm", "Proceed?"),
                Duration.ofSeconds(60), InteractionManager.CURRENT_GENERATION);
    }
}
