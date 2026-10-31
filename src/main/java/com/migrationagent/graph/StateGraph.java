package com.migrationagent.graph;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny LangGraph-style state machine: named nodes that mutate a shared state object, plain edges,
 * and conditional edges whose router returns a label that is resolved to the next node.
 *
 * <p>It is deliberately dependency-free so the agent core compiles and tests without any framework.
 * The API mirrors LangGraph (addNode / addEdge / addConditionalEdges / compile / invoke), so swapping in
 * LangGraph4j later only means re-declaring the graph in {@code MigrationGraphFactory}.
 */
public final class StateGraph<S> {

    public static final String START = "__start__";
    public static final String END = "__end__";

    @FunctionalInterface
    public interface Node<S> {
        void apply(S state) throws Exception;
    }

    @FunctionalInterface
    public interface Router<S> {
        String route(S state);
    }

    public interface Listener<S> {
        default void onNodeStart(String node, S state) {}

        default void onNodeEnd(String node, S state, Duration took) {}

        default void onNodeError(String node, S state, Throwable error) {}
    }

    public static final class GraphExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String node;

        public GraphExecutionException(String node, Throwable cause) {
            super("Node '" + node + "' failed: " + cause, cause);
            this.node = node;
        }

        public String node() {
            return node;
        }
    }

    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Map<String, Router<S>> routers = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> routes = new LinkedHashMap<>();
    private int maxSteps = 200;

    public StateGraph<S> addNode(String name, Node<S> node) {
        if (START.equals(name) || END.equals(name)) {
            throw new IllegalArgumentException("Reserved node name: " + name);
        }
        if (nodes.putIfAbsent(name, node) != null) {
            throw new IllegalArgumentException("Duplicate node: " + name);
        }
        return this;
    }

    public StateGraph<S> addEdge(String from, String to) {
        ensureNoOutgoing(from);
        edges.put(from, to);
        return this;
    }

    /** {@code targets} maps the label returned by the router to the name of the next node (or END). */
    public StateGraph<S> addConditionalEdges(String from, Router<S> router, Map<String, String> targets) {
        ensureNoOutgoing(from);
        routers.put(from, router);
        routes.put(from, new LinkedHashMap<>(targets));
        return this;
    }

    /** Safety valve against accidental infinite loops. */
    public StateGraph<S> maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    private void ensureNoOutgoing(String from) {
        if (edges.containsKey(from) || routers.containsKey(from)) {
            throw new IllegalArgumentException("Node already has an outgoing edge: " + from);
        }
    }

    public CompiledGraph<S> compile() {
        if (!edges.containsKey(START)) {
            throw new IllegalStateException("Graph has no edge from START");
        }
        for (String from : edges.keySet()) {
            requireSource(from);
            requireTarget(edges.get(from));
        }
        for (String from : routes.keySet()) {
            requireSource(from);
            routes.get(from).values().forEach(this::requireTarget);
        }
        for (String node : nodes.keySet()) {
            if (!edges.containsKey(node) && !routers.containsKey(node)) {
                throw new IllegalStateException("Node has no outgoing edge: " + node);
            }
        }
        return new CompiledGraph<>(new LinkedHashMap<>(nodes), new LinkedHashMap<>(edges),
                new LinkedHashMap<>(routers), routes, maxSteps);
    }

    private void requireSource(String from) {
        if (!START.equals(from) && !nodes.containsKey(from)) {
            throw new IllegalStateException("Edge from unknown node: " + from);
        }
    }

    private void requireTarget(String to) {
        if (!END.equals(to) && !nodes.containsKey(to)) {
            throw new IllegalStateException("Edge to unknown node: " + to);
        }
    }

    /** Mermaid flowchart of the graph, handy for docs and dashboards. */
    public String toMermaid() {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        edges.forEach((from, to) -> sb.append("  ").append(id(from)).append(" --> ").append(id(to)).append('\n'));
        routes.forEach((from, targets) -> targets.forEach((label, to) ->
                sb.append("  ").append(id(from)).append(" -->|").append(label).append("| ").append(id(to)).append('\n')));
        return sb.toString();
    }

    private static String id(String n) {
        return switch (n) {
            case START -> "START((start))";
            case END -> "END((end))";
            default -> n;
        };
    }

    public static final class CompiledGraph<S> {
        private final Map<String, Node<S>> nodes;
        private final Map<String, String> edges;
        private final Map<String, Router<S>> routers;
        private final Map<String, Map<String, String>> routes;
        private final int maxSteps;

        private CompiledGraph(Map<String, Node<S>> nodes, Map<String, String> edges, Map<String, Router<S>> routers,
                              Map<String, Map<String, String>> routes, int maxSteps) {
            this.nodes = nodes;
            this.edges = edges;
            this.routers = routers;
            this.routes = routes;
            this.maxSteps = maxSteps;
        }

        public S invoke(S state) {
            return invoke(state, new Listener<>() {});
        }

        public S invoke(S state, Listener<S> listener) {
            String current = edges.get(START);
            int steps = 0;
            while (!END.equals(current)) {
                if (++steps > maxSteps) {
                    throw new IllegalStateException("Graph exceeded " + maxSteps + " steps (loop?) at node " + current);
                }
                Node<S> node = nodes.get(current);
                listener.onNodeStart(current, state);
                long t0 = System.nanoTime();
                try {
                    node.apply(state);
                } catch (Exception e) {
                    listener.onNodeError(current, state, e);
                    throw new GraphExecutionException(current, e);
                }
                listener.onNodeEnd(current, state, Duration.ofNanos(System.nanoTime() - t0));
                current = next(current, state);
            }
            return state;
        }

        private String next(String current, S state) {
            Router<S> router = routers.get(current);
            if (router == null) {
                return edges.get(current);
            }
            String label = router.route(state);
            String target = routes.get(current).get(label);
            if (target == null) {
                throw new IllegalStateException("Router at '" + current + "' returned unknown label: " + label);
            }
            return target;
        }
    }
}
