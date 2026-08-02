package cc.kites.mineclaw.api;

/** Token usage returned by an OpenAI-compatible endpoint. Missing fields remain {@code null}. */
public record ApiUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
