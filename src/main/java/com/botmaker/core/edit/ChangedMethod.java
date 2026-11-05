package com.botmaker.core.edit;

import java.util.List;

/**
 * A method as an edit left it: its source, for the client to draw, and where each node's id sits in that source.
 * This is the one place Java text crosses, and only from the core to a client.
 *
 * @param id     the method declaration's {@code BlockId}
 * @param name   its name, {@code run} or {@code Main.run}'s {@code run}
 * @param source its source text, from its first modifier to its closing brace
 * @param nodes  every node a client may address, by id, with its offset and length within {@code source}
 */
public record ChangedMethod(String id, String name, String source, List<Node> nodes) {

    public ChangedMethod {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("a changed method needs its id");
        if (source == null) throw new IllegalArgumentException("a changed method needs its source");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        for (Node node : nodes) {
            if (node.start() + node.length() > source.length()) {
                throw new IllegalArgumentException("a node past its method's source: " + node.id());
            }
        }
    }

    /** One node of the method, at {@code start} for {@code length} characters of its source. */
    public record Node(String id, int start, int length) {
        public Node {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("a node needs its id");
            if (start < 0 || length < 0) throw new IllegalArgumentException("a node outside its source: " + id);
        }
    }
}
