package cc.kites.mineclaw.session;

import java.util.ArrayList;
import java.util.List;

/** Server-wide, in-memory public conversation history. */
public final class PublicSession {
    private final ArrayList<Message> messages = new ArrayList<>();

    public synchronized List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized List<Message> snapshot(int maxMessages) {
        int keep = pairAligned(maxMessages);
        int from = Math.max(0, messages.size() - keep);
        return List.copyOf(messages.subList(from, messages.size()));
    }

    public synchronized void appendCompletedTurn(String user, String assistant, int maxMessages) {
        messages.add(new Message("user", user));
        messages.add(new Message("assistant", assistant));
        trim(maxMessages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }

    public static int approximateTokens(List<Message> history, String currentUser) {
        long characters = currentUser == null ? 0L : currentUser.length();
        for (Message message : history) {
            characters += message.content().length() + message.role().length() + 8L;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (characters + 3L) / 4L));
    }

    private void trim(int maxMessages) {
        int keep = pairAligned(maxMessages);
        if (messages.size() > keep) {
            messages.subList(0, messages.size() - keep).clear();
        }
    }

    private static int pairAligned(int maxMessages) {
        int keep = Math.max(0, maxMessages);
        return keep - keep % 2;
    }

    public record Message(String role, String content) {
        public Message {
            if (!(role.equals("user") || role.equals("assistant"))) {
                throw new IllegalArgumentException("Public Session only accepts user/assistant messages");
            }
            content = content == null ? "" : content;
        }
    }
}
