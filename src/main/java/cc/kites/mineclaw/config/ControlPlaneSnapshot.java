package cc.kites.mineclaw.config;

import java.util.Objects;

/** One atomically published control-plane generation. */
public record ControlPlaneSnapshot(MineclawConfig config, ProviderCatalog providers,
                                   CommandWhitelist whitelist) {
    public ControlPlaneSnapshot {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(whitelist, "whitelist");
    }
}
