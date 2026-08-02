package cc.kites.mineclaw.commandexec;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Bukkit-independent asynchronous boundary used by the command policy and its tests. */
public interface CommandRuntime {
    CompletionStage<Optional<OnlinePlayer>> findOnlinePlayer(String nameOrUuid);

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

    record OnlinePlayer(UUID uuid, String name) {
        public OnlinePlayer {
            java.util.Objects.requireNonNull(uuid, "uuid");
            name = java.util.Objects.requireNonNull(name, "name");
        }
    }
}
