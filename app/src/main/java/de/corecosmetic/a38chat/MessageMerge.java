package de.corecosmetic.a38chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MessageMerge {
    private MessageMerge() {
    }

    static List<ChatApi.Message> appendUnique(
            List<ChatApi.Message> target,
            Set<Integer> knownIds,
            List<ChatApi.Message> incoming
    ) {
        ArrayList<ChatApi.Message> additions = new ArrayList<>();
        for (ChatApi.Message message : incoming) {
            if (message.id > 0 && knownIds.add(message.id)) {
                target.add(message);
                additions.add(message);
            }
        }
        return additions;
    }

    static List<ChatApi.Message> mergeRecent(
            List<ChatApi.Message> existing,
            List<ChatApi.Message> incoming,
            int maximum
    ) {
        Map<Integer, ChatApi.Message> byId = new LinkedHashMap<>();
        for (ChatApi.Message message : existing) {
            if (message.id > 0) {
                byId.put(message.id, message);
            }
        }
        for (ChatApi.Message message : incoming) {
            if (message.id > 0) {
                byId.put(message.id, message);
            }
        }
        ArrayList<ChatApi.Message> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingInt(item -> item.id));
        int safeMaximum = Math.max(1, maximum);
        if (merged.size() > safeMaximum) {
            return new ArrayList<>(merged.subList(merged.size() - safeMaximum, merged.size()));
        }
        return merged;
    }
}
