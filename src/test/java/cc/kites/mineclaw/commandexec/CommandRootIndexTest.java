package cc.kites.mineclaw.commandexec;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRootIndexTest {
    @Test
    void resolvesLifecycleAndNamespacedRootsWithoutEvaluatingRequirements() {
        CommandRootIndex index = new CommandRootIndex();
        assertThat(index.resolve("kp warp list")).isEqualTo(CommandRootIndex.Resolution.UNKNOWN);

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("kp")
                .requires(ignored -> false));
        dispatcher.register(LiteralArgumentBuilder.literal("minecraft:tp"));
        dispatcher.register(LiteralArgumentBuilder.literal("home"));
        index.publish(dispatcher);

        assertThat(index.resolve("KP warp list")).isEqualTo(CommandRootIndex.Resolution.FOUND);
        assertThat(index.resolve("/minecraft:TP Player 0 64 0"))
                .isEqualTo(CommandRootIndex.Resolution.FOUND);
        assertThat(index.resolve("home")).isEqualTo(CommandRootIndex.Resolution.FOUND);
        assertThat(index.resolve("missing argument")).isEqualTo(CommandRootIndex.Resolution.MISSING);
    }

    @Test
    void publishingACommandsReloadReplacesTheDispatcherView() {
        CommandRootIndex index = new CommandRootIndex();
        CommandDispatcher<CommandSourceStack> first = new CommandDispatcher<>();
        first.register(LiteralArgumentBuilder.literal("oldroot"));
        index.publish(first);
        assertThat(index.resolve("oldroot")).isEqualTo(CommandRootIndex.Resolution.FOUND);

        CommandDispatcher<CommandSourceStack> reloaded = new CommandDispatcher<>();
        reloaded.register(LiteralArgumentBuilder.literal("newroot"));
        index.publish(reloaded);

        assertThat(index.resolve("oldroot")).isEqualTo(CommandRootIndex.Resolution.MISSING);
        assertThat(index.resolve("newroot")).isEqualTo(CommandRootIndex.Resolution.FOUND);
    }
}
