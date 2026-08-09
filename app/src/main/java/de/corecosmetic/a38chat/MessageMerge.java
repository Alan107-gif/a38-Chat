package de.corecosmetic.a38chat;

import java.util.ArrayList;
import java.util.List;
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
}
