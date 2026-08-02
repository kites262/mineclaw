package cc.kites.mineclaw.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes only completely parsed and validated config snapshots. */
public final class ConfigStore {
    private final Path path;
    private final ConfigLoader loader;
    private final AtomicReference<MineclawConfig> current = new AtomicReference<>();

    public ConfigStore(Path path) {
        this(path, new ConfigLoader());
    }

    public ConfigStore(Path path, ConfigLoader loader) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public synchronized MineclawConfig loadInitial() throws ConfigException {
        return reload();
    }

    /** Parse first and swap second, so a failed reload never exposes a partial snapshot. */
    public synchronized MineclawConfig reload() throws ConfigException {
        MineclawConfig candidate = loader.load(path);
        current.set(candidate);
        return candidate;
    }

    public MineclawConfig get() {
        MineclawConfig value = current.get();
        if (value == null) {
            throw new IllegalStateException("Configuration has not been loaded");
        }
        return value;
    }

    public MineclawConfig current() {
        return get();
    }

    public Path path() {
        return path;
    }
}
