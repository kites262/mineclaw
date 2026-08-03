package cc.kites.mineclaw.commandexec;

import cc.kites.mineclaw.interaction.InteractionManager;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Bukkit-independent asynchronous boundary used by the command policy and its tests. */
public interface CommandRuntime {
    CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid);

    /** Resolves an accurate online account name and rejects case or UUID aliases. */
    default CompletionStage<Optional<OnlinePlayer>> findOnlinePlayerExact(String accountName) {
        return findOnlinePlayer(accountName).thenApply(found ->
                found.filter(player -> player.name().equals(accountName)));
    }

    CompletionStage<CommandDispatchResult> executeConsole(String command);

    default CompletionStage<CommandDispatchResult> executeConsoleGuarded(String command, BooleanSupplier guard) {
        return guard.getAsBoolean()
                ? executeConsole(command)
                : CompletableFuture.completedFuture(CommandDispatchResult.dispatchRejected());
    }

    CompletionStage<CommandDispatchResult> executePlayer(OnlinePlayer player, String command);

    default CompletionStage<CommandDispatchResult> executePlayerGuarded(OnlinePlayer player, String command,
                                                                        BooleanSupplier guard) {
        return guard.getAsBoolean()
                ? executePlayer(player, command)
                : CompletableFuture.completedFuture(CommandDispatchResult.dispatchRejected());
    }

    CompletionStage<Boolean> send(OnlinePlayer player, String messageKey, Map<String, String> values);

    /** Dedicated boundary for a private approval prompt with token-bound action controls. */
    default CompletionStage<Boolean> sendApprovalPrompt(OnlinePlayer player, Map<String, String> values) {
        return send(player, "approve_prompt", values);
    }

    /** Sends an approval card only while its owning invocation/policy is still active. */
    default CompletionStage<Boolean> sendApprovalPromptGuarded(
            OnlinePlayer player,
            Map<String, String> values,
            BooleanSupplier guard
    ) {
        Objects.requireNonNull(guard, "guard");
        return guard.getAsBoolean()
                ? sendApprovalPrompt(player, values)
                : CompletableFuture.completedFuture(Boolean.FALSE);
    }

    /** Dedicated private-delivery boundary for a Java-rendered generic interaction card. */
    default CompletionStage<Boolean> sendInteractionPrompt(OnlinePlayer player, String token,
                                                           InteractionManager.Interaction interaction) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("generic interaction prompts are unavailable"));
    }

    /** Sends a generic interaction card only while its owning invocation remains active. */
    default CompletionStage<Boolean> sendInteractionPromptGuarded(
            OnlinePlayer player,
            String token,
            InteractionManager.Interaction interaction,
            BooleanSupplier guard
    ) {
        Objects.requireNonNull(guard, "guard");
        return guard.getAsBoolean()
                ? sendInteractionPrompt(player, token, interaction)
                : CompletableFuture.completedFuture(Boolean.FALSE);
    }

    record OnlinePlayer(UUID uuid, String name) {
        public OnlinePlayer {
            java.util.Objects.requireNonNull(uuid, "uuid");
            name = java.util.Objects.requireNonNull(name, "name");
        }
    }
}
