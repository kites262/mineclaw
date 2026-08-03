package cc.kites.mineclaw.config;

import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Parses config.yml, providers.yml and whitelist.yml before one atomic publication. */
public final class ControlPlaneStore {
    private final Path dataRoot;
    private final ConfigLoader configLoader;
    private final ProviderCatalogLoader providerLoader;
    private final CommandWhitelistLoader whitelistLoader;
    private final AtomicReference<ControlPlaneSnapshot> current = new AtomicReference<>();

    public ControlPlaneStore(Path dataRoot) {
        this(dataRoot, new ConfigLoader(), new ProviderCatalogLoader(), new CommandWhitelistLoader());
    }

    public ControlPlaneStore(Path dataRoot, ConfigLoader configLoader,
                             ProviderCatalogLoader providerLoader,
                             CommandWhitelistLoader whitelistLoader) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.providerLoader = Objects.requireNonNull(providerLoader, "providerLoader");
        this.whitelistLoader = Objects.requireNonNull(whitelistLoader, "whitelistLoader");
    }

    public synchronized ControlPlaneSnapshot loadInitial() throws ConfigException {
        return reload();
    }

    /**
     * Installs a closed synthetic snapshot so management commands can remain available after a
     * cold-start configuration failure. Conversation admission must remain closed until reload succeeds.
     */
    public synchronized ControlPlaneSnapshot initializeUnavailable() {
        if (current.get() != null) {
            throw new IllegalStateException("Control plane has already been initialized");
        }
        MineclawConfig config = MineclawConfig.defaults();
        ProviderCatalog.Provider provider = new ProviderCatalog.Provider("unavailable",
                new ProviderCatalog.Api(ProviderCatalog.ApiType.OPENAI_CHAT_COMPLETIONS,
                        URI.create("http://127.0.0.1"), "unavailable"),
                new ProviderCatalog.Transport(Duration.ofSeconds(1), 0, Duration.ZERO), List.of());
        ProviderCatalog.Model model = new ProviderCatalog.Model("unavailable/unavailable", "unavailable",
                "unavailable", new ProviderCatalog.Limits(1024, 1), Optional.empty(), new JsonObject());
        ProviderCatalog providers = new ProviderCatalog(model.reference(),
                Map.of(provider.id(), provider), Map.of(model.reference(), model));
        ControlPlaneSnapshot snapshot = new ControlPlaneSnapshot(config, providers,
                new CommandWhitelist(false, List.of(), List.of()));
        current.set(snapshot);
        return snapshot;
    }

    public synchronized ControlPlaneSnapshot reload() throws ConfigException {
        MineclawConfig config = configLoader.load(dataRoot.resolve("config.yml"));
        ProviderCatalog providers = providerLoader.load(dataRoot, dataRoot.resolve(ProviderCatalogLoader.FILE_NAME));
        CommandWhitelist whitelist = whitelistLoader.load(dataRoot,
                dataRoot.resolve(CommandWhitelistLoader.FILE_NAME));
        ControlPlaneSnapshot candidate = new ControlPlaneSnapshot(config, providers, whitelist);
        current.set(candidate);
        return candidate;
    }

    public ControlPlaneSnapshot get() {
        ControlPlaneSnapshot value = current.get();
        if (value == null) {
            throw new IllegalStateException("Control plane has not been loaded");
        }
        return value;
    }
}
