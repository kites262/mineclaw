package cc.kites.mineclaw.javascript;

import org.graalvm.polyglot.Source;

import java.util.Objects;

/** Opaque, validated source snapshot safe to retain in an immutable FunctionCatalog. */
public final class PreparedScript {
    private final String functionName;
    private final int apiVersion;
    private final String scriptHash;
    private final Source source;

    PreparedScript(String functionName, int apiVersion, String scriptHash, Source source) {
        this.functionName = Objects.requireNonNull(functionName, "functionName");
        this.apiVersion = apiVersion;
        this.scriptHash = Objects.requireNonNull(scriptHash, "scriptHash");
        this.source = Objects.requireNonNull(source, "source");
    }

    public String functionName() {
        return functionName;
    }

    public int apiVersion() {
        return apiVersion;
    }

    public String scriptHash() {
        return scriptHash;
    }

    Source source() {
        return source;
    }
}
