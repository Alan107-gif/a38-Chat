package de.corecosmetic.a38chat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class MessageMergeTest {
    @Test
    public void appendUniquePreservesOrderAndRejectsDuplicates() {
        List<ChatApi.Message> target = new ArrayList<>();
        Set<Integer> knownIds = new HashSet<>();

        List<ChatApi.Message> first = MessageMerge.appendUnique(
                target,
                knownIds,
                Arrays.asList(message(1), message(1), message(0), message(2))
        );
        List<ChatApi.Message> second = MessageMerge.appendUnique(
                target,
                knownIds,
                Arrays.asList(message(2), message(3))
        );

        assertEquals(Arrays.asList(1, 2), ids(first));
        assertEquals(Arrays.asList(3), ids(second));
        assertEquals(Arrays.asList(1, 2, 3), ids(target));
    }

    private ChatApi.Message message(int id) {
        return new ChatApi.Message(id, "sender", "recipient", "text", "text", 0, 0, "now");
    }

    private List<Integer> ids(List<ChatApi.Message> messages) {
        ArrayList<Integer> result = new ArrayList<>();
        for (ChatApi.Message message : messages) {
            result.add(message.id);
        }
        return result;
    }
}
