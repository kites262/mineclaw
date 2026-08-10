package cc.kites.mineclaw.session;

import cc.kites.mineclaw.api.ApiMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-wide, in-memory public conversation history, retained as indivisible Turns. */
public final class PublicSession {
    /** Lossless completed-Turn archive for the lifetime of this Session. */
    private final ArrayList<List<ApiMessage>> turns = new ArrayList<>();
    /** Bounded/compacted projection used to construct model requests. */
    private final ArrayList<List<ApiMessage>> contextTurns = new ArrayList<>();
    private String summary = "";
    private String promptCacheKey = newPromptCacheKey();
    private long revision;

    public synchronized List<ApiMessage> snapshot() {
        return flatten(turns);
    }

    public synchronized List<ApiMessage> snapshot(int maxMessages) {
        return flatten(selectedContextTurns(maxMessages));
    }

    /** Immutable summary plus complete raw-Turn boundaries used to build one active Turn. */
    public synchronized Snapshot snapshotState(int maxMessages) {
        return new Snapshot(revision, summary, selectedContextTurns(maxMessages), promptCacheKey);
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

    /** Stores a successful Turn with every assistant Tool Call and matching Tool Result intact. */
    public synchronized void appendCompletedTurn(List<ApiMessage> completedTurn, int maxMessages) {
        validateCompletedTurn(completedTurn);
        appendTurn(completedTurn, maxMessages);
    }

    public synchronized void clear() {
        turns.clear();
        contextTurns.clear();
        summary = "";
        promptCacheKey = newPromptCacheKey();
        revision++;
    }

    /** Number of losslessly archived API messages across complete successful Turns. */
    public synchronized int size() {
        return turns.stream().mapToInt(List::size).sum();
    }

    private void appendTurn(List<ApiMessage> turn, int maxMessages) {
        List<ApiMessage> immutable = List.copyOf(Objects.requireNonNull(turn, "turn"));
        if (immutable.isEmpty()) {
            throw new IllegalArgumentException("Turn must contain at least one message");
        }
        turns.add(immutable);
        contextTurns.add(immutable);
        trimContext(maxMessages);
        revision++;
    }

    private Optional<Snapshot> publishReplacement(String replacementSummary,
                                                  List<List<ApiMessage>> retainedTurns,
                                                  int maxMessages) {
        summary = replacementSummary;
        contextTurns.clear();
        retainedTurns.forEach(turn -> contextTurns.add(List.copyOf(turn)));
        trimContext(maxMessages);
        revision++;
        return Optional.of(snapshotState(maxMessages));
    }

    private void trimContext(int maxMessages) {
        if (maxMessages <= 0) {
            contextTurns.clear();
            return;
        }
        while (contextTurns.size() > 1 && contextSize() > maxMessages) {
            contextTurns.removeFirst();
        }
    }

    private int contextSize() {
        return contextTurns.stream().mapToInt(List::size).sum();
    }

    private List<List<ApiMessage>> selectedContextTurns(int maxMessages) {
        if (maxMessages <= 0 || contextTurns.isEmpty()) {
            return List.of();
        }
        int from = contextTurns.size() - 1;
        int count = contextTurns.get(from).size();
        while (from > 0 && count + contextTurns.get(from - 1).size() <= maxMessages) {
            count += contextTurns.get(--from).size();
        }
        return List.copyOf(contextTurns.subList(from, contextTurns.size()));
    }

    private static List<ApiMessage> flatten(List<List<ApiMessage>> source) {
        ArrayList<ApiMessage> flattened = new ArrayList<>();
        source.forEach(flattened::addAll);
        return List.copyOf(flattened);
    }

    private static void validateCompletedTurn(List<ApiMessage> turn) {
        Objects.requireNonNull(turn, "turn");
        if (turn.size() < 2 || !turn.getFirst().role().equals("user")) {
            throw new IllegalArgumentException("completed Turn must begin with a user message");
        }
        ApiMessage last = turn.getLast();
        if (!last.role().equals("assistant") || !last.toolCalls().isEmpty()
                || last.content() == null || last.content().isBlank()) {
            throw new IllegalArgumentException("completed Turn must end with a final assistant message");
        }
        Set<String> pending = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (ApiMessage message : turn) {
            if (message.role().equals("assistant") && !message.toolCalls().isEmpty()) {
                if (!pending.isEmpty()) {
                    throw new IllegalArgumentException("assistant Tool Call frames must not overlap");
                }
                message.toolCalls().forEach(call -> {
                    if (!seen.add(call.id())) {
                        throw new IllegalArgumentException("duplicate Tool Call id in completed Turn: " + call.id());
                    }
                    pending.add(call.id());
                });
            } else if (message.role().equals("tool")) {
                if (message.toolCallId() == null || !pending.remove(message.toolCallId())) {
                    throw new IllegalArgumentException("Tool Result has no pending Tool Call: "
                            + message.toolCallId());
                }
            } else if (!pending.isEmpty()) {
                throw new IllegalArgumentException("completed Turn contains an incomplete Tool Call frame");
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("completed Turn contains an incomplete Tool Call frame");
        }
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
