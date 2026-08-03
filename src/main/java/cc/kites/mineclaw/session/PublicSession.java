package cc.kites.mineclaw.session;

import cc.kites.mineclaw.api.ApiMessage;
import cc.kites.mineclaw.api.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-wide, in-memory public conversation history, retained as indivisible Turns. */
public final class PublicSession {
    private static final String INTERRUPTED_TOOL_RESULT = """
            {"status":"cancelled","output":{"error_code":"turn_interrupted","message":"Tool call did not complete before the Turn ended"}}
            """.strip();

    private final ArrayList<List<ApiMessage>> turns = new ArrayList<>();
    private String summary = "";
    private String promptCacheKey = newPromptCacheKey();
    private long revision;

    public synchronized List<ApiMessage> snapshot() {
        return flatten(turns);
    }

    public synchronized List<ApiMessage> snapshot(int maxMessages) {
        return flatten(selectedTurns(maxMessages));
    }

    /** Immutable summary plus complete raw-Turn boundaries used to build one active Turn. */
    public synchronized Snapshot snapshotState(int maxMessages) {
        return new Snapshot(revision, summary, selectedTurns(maxMessages), promptCacheKey);
    }

    /** Publishes a successful model-generated summary only if the source Session is unchanged. */
    public synchronized Optional<Snapshot> publishCompaction(long expectedRevision, String newSummary,
                                                             List<List<ApiMessage>> retainedTurns,
                                                             int maxMessages) {
        Objects.requireNonNull(newSummary, "newSummary");
        Objects.requireNonNull(retainedTurns, "retainedTurns");
        if (revision != expectedRevision || newSummary.isBlank()) {
            return Optional.empty();
        }
        return publishReplacement(newSummary, retainedTurns, maxMessages);
    }

    /** Stores a successful Turn compactly as the public user request and final assistant answer. */
    public synchronized void appendCompletedTurn(String user, String assistant, int maxMessages) {
        appendTurn(List.of(ApiMessage.user(user), ApiMessage.assistant(assistant)), maxMessages);
    }

    /**
     * Stores a failed Turn with its complete Tool transcript and a synthetic terminal assistant message.
     * Any Tool call interrupted by the failure receives a stable cancellation result so the next API request
     * never contains an incomplete assistant/tool-call frame.
     */
    public synchronized void appendFailedTurn(List<ApiMessage> partialTurn, String outcome,
                                              int maxMessages) {
        Objects.requireNonNull(partialTurn, "partialTurn");
        Objects.requireNonNull(outcome, "outcome");
        if (partialTurn.isEmpty() || !partialTurn.getFirst().role().equals("user")) {
            throw new IllegalArgumentException("failed Turn must begin with a user message");
        }
        ArrayList<ApiMessage> completed = new ArrayList<>(partialTurn.size() + 2);
        LinkedHashMap<String, ToolCall> pending = new LinkedHashMap<>();
        for (ApiMessage message : partialTurn) {
            if (message.role().equals("assistant") && !message.toolCalls().isEmpty()) {
                if (!pending.isEmpty()) {
                    appendInterruptedResults(completed, pending);
                }
                completed.add(message);
                message.toolCalls().forEach(call -> pending.put(call.id(), call));
            } else if (message.role().equals("tool") && message.toolCallId() != null) {
                completed.add(message);
                pending.remove(message.toolCallId());
            } else {
                if (!pending.isEmpty()) {
                    appendInterruptedResults(completed, pending);
                }
                completed.add(message);
            }
        }
        appendInterruptedResults(completed, pending);
        completed.add(ApiMessage.assistant(outcome));
        appendTurn(completed, maxMessages);
    }

    public synchronized void clear() {
        turns.clear();
        summary = "";
        promptCacheKey = newPromptCacheKey();
        revision++;
    }

    /** Number of API messages currently retained, including failed-Turn Tool frames. */
    public synchronized int size() {
        return turns.stream().mapToInt(List::size).sum();
    }

    private void appendTurn(List<ApiMessage> turn, int maxMessages) {
        List<ApiMessage> immutable = List.copyOf(Objects.requireNonNull(turn, "turn"));
        if (immutable.isEmpty()) {
            throw new IllegalArgumentException("Turn must contain at least one message");
        }
        turns.add(immutable);
        trim(maxMessages);
        revision++;
    }

    private Optional<Snapshot> publishReplacement(String replacementSummary,
                                                  List<List<ApiMessage>> retainedTurns,
                                                  int maxMessages) {
        summary = replacementSummary;
        turns.clear();
        retainedTurns.forEach(turn -> turns.add(List.copyOf(turn)));
        trim(maxMessages);
        revision++;
        return Optional.of(snapshotState(maxMessages));
    }

    private void trim(int maxMessages) {
        if (maxMessages <= 0) {
            turns.clear();
            return;
        }
        while (turns.size() > 1 && size() > maxMessages) {
            turns.removeFirst();
        }
    }

    private List<List<ApiMessage>> selectedTurns(int maxMessages) {
        if (maxMessages <= 0 || turns.isEmpty()) {
            return List.of();
        }
        int from = turns.size() - 1;
        int count = turns.get(from).size();
        while (from > 0 && count + turns.get(from - 1).size() <= maxMessages) {
            count += turns.get(--from).size();
        }
        return List.copyOf(turns.subList(from, turns.size()));
    }

    private static List<ApiMessage> flatten(List<List<ApiMessage>> source) {
        ArrayList<ApiMessage> flattened = new ArrayList<>();
        source.forEach(flattened::addAll);
        return List.copyOf(flattened);
    }

    private static void appendInterruptedResults(List<ApiMessage> target,
                                                 LinkedHashMap<String, ToolCall> pending) {
        pending.keySet().forEach(callId -> target.add(ApiMessage.tool(callId, INTERRUPTED_TOOL_RESULT)));
        pending.clear();
    }

    private static String newPromptCacheKey() {
        return "mineclaw:" + UUID.randomUUID();
    }

    public record Snapshot(long revision, String summary, List<List<ApiMessage>> turns,
                           String promptCacheKey) {
        public Snapshot {
            summary = Objects.requireNonNull(summary, "summary");
            turns = Objects.requireNonNull(turns, "turns").stream().map(List::copyOf).toList();
            promptCacheKey = Objects.requireNonNull(promptCacheKey, "promptCacheKey");
        }

        public List<ApiMessage> messages() {
            return flatten(turns);
        }
    }
}
