package cc.kites.mineclaw.workspace;

import java.util.Objects;

/** One hot-read AGENTS.md snapshot as injected into a single model request. */
public record AgentDocument(
        String content,
        String displayName,
        boolean seeded,
        boolean truncated,
        int sourceLength
) {
    public AgentDocument {
        content = Objects.requireNonNull(content, "content");
        displayName = Objects.requireNonNull(displayName, "displayName");
        if (sourceLength < content.length()) {
            throw new IllegalArgumentException("sourceLength cannot be shorter than content");
        }
        if (truncated != (sourceLength > content.length())) {
            throw new IllegalArgumentException("truncated must describe sourceLength versus content length");
        }
    }
}
