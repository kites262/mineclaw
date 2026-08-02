package cc.kites.mineclaw.tool;

import cc.kites.mineclaw.support.FoliaTasks;
import com.google.gson.JsonArray;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentToolsTest {
    private static final UUID TURN_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void onlinePlayersReturnsOnlyStableNamesReadFromEachEntityOwner() {
        Harness harness = new Harness();
        harness.addPlayer("zed", true);
        harness.addPlayer("Caller", true);
        harness.addPlayer("Ghost", false);
        harness.addPlayer("Alice", true);

        ToolResult result = harness.tools.execute("online_players", TURN_PLAYER, "Caller",
                new EnvironmentTools.Settings(12, 0L, false, 36)).join();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.output().keySet())
                .containsExactlyInAnyOrder("status", "turn_player", "online_players");
        assertThat(result.output().get("turn_player").getAsString()).isEqualTo("Caller");
        JsonArray online = result.output().getAsJsonArray("online_players");
        assertThat(online.asList()).extracting(element -> element.getAsString())
                .containsExactly("Alice", "Caller", "zed");
        assertThat(result.output().toString()).doesNotContain(TURN_PLAYER.toString());
        assertThat(harness.globalScheduler.executeCalls).hasValue(1);
        assertThat(harness.entityExecutions).hasValue(4);
    }

    private static final class Harness {
        private final AtomicBoolean globalOwner = new AtomicBoolean();
        private final AtomicReference<String> entityOwner = new AtomicReference<>();
        private final AtomicInteger entityExecutions = new AtomicInteger();
        private final List<Player> players = new ArrayList<>();
        private final ImmediateGlobalScheduler globalScheduler = new ImmediateGlobalScheduler(globalOwner);
        private final Server server = proxy(Server.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getGlobalRegionScheduler" -> globalScheduler;
            case "getAsyncScheduler" -> new NoopAsyncScheduler();
            case "getOnlinePlayers" -> onlinePlayers();
            default -> defaultValue(method.getReturnType());
        });
        private final Plugin plugin = proxy(Plugin.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "isEnabled" -> true;
            default -> defaultValue(method.getReturnType());
        });
        private final EnvironmentTools tools = new EnvironmentTools(server, new FoliaTasks(plugin));

        private void addPlayer(String name, boolean online) {
            EntityScheduler scheduler = new ImmediateEntityScheduler(name, entityOwner, entityExecutions);
            Player player = proxy(Player.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "getScheduler" -> scheduler;
                case "isOnline" -> {
                    requireOwner(name);
                    yield online;
                }
                case "getName" -> {
                    requireOwner(name);
                    yield name;
                }
                default -> defaultValue(method.getReturnType());
            });
            players.add(player);
        }

        private Collection<? extends Player> onlinePlayers() {
            if (!globalOwner.get()) {
                throw new AssertionError("online player collection read outside GlobalScheduler");
            }
            return List.copyOf(players);
        }

        private void requireOwner(String name) {
            if (!name.equals(entityOwner.get())) {
                throw new AssertionError("player state read outside its EntityScheduler: " + name);
            }
        }
    }

    private static final class ImmediateGlobalScheduler implements GlobalRegionScheduler {
        private final AtomicBoolean owner;
        private final AtomicInteger executeCalls = new AtomicInteger();

        private ImmediateGlobalScheduler(AtomicBoolean owner) {
            this.owner = owner;
        }

        @Override
        public void execute(Plugin plugin, Runnable run) {
            executeCalls.incrementAndGet();
            if (!owner.compareAndSet(false, true)) {
                throw new AssertionError("nested GlobalScheduler execution");
            }
            try {
                run.run();
            } finally {
                owner.set(false);
            }
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) { }
    }

    private static final class ImmediateEntityScheduler implements EntityScheduler {
        private final String name;
        private final AtomicReference<String> owner;
        private final AtomicInteger executions;

        private ImmediateEntityScheduler(String name, AtomicReference<String> owner, AtomicInteger executions) {
            this.name = name;
            this.owner = owner;
            this.executions = executions;
        }

        @Override
        public boolean execute(Plugin plugin, Runnable run, Runnable retired, long delay) {
            executions.incrementAndGet();
            if (!owner.compareAndSet(null, name)) {
                throw new AssertionError("nested EntityScheduler execution");
            }
            try {
                run.run();
            } finally {
                owner.set(null);
            }
            return true;
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task, Runnable retired) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        Runnable retired, long delayTicks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            Runnable retired, long initialDelayTicks, long periodTicks) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopAsyncScheduler implements AsyncScheduler {
        @Override
        public ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                                        long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                            long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelTasks(Plugin plugin) { }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
