package com.migrationagent.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.migrationagent.graph.StateGraph.END;
import static com.migrationagent.graph.StateGraph.START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateGraphTest {

    static final class Counter {
        int value;
        final List<String> trail = new ArrayList<>();
    }

    @Test
    void conditionalLoopRunsUntilRouterSaysDone() {
        var graph = new StateGraph<Counter>()
                .addNode("inc", s -> { s.value++; s.trail.add("inc"); })
                .addNode("done", s -> s.trail.add("done"))
                .addEdge(START, "inc")
                .addConditionalEdges("inc", s -> s.value < 3 ? "again" : "finish",
                        Map.of("again", "inc", "finish", "done"))
                .addEdge("done", END)
                .compile();

        Counter result = graph.invoke(new Counter());

        assertEquals(3, result.value);
        assertEquals(List.of("inc", "inc", "inc", "done"), result.trail);
    }

    @Test
    void listenerSeesEveryNode() {
        var graph = new StateGraph<Counter>()
                .addNode("a", s -> { })
                .addNode("b", s -> { })
                .addEdge(START, "a").addEdge("a", "b").addEdge("b", END)
                .compile();
        List<String> seen = new ArrayList<>();
        graph.invoke(new Counter(), new StateGraph.Listener<>() {
            @Override
            public void onNodeStart(String node, Counter state) {
                seen.add(node);
            }
        });
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void nodeFailureIsWrappedWithTheNodeName() {
        var graph = new StateGraph<Counter>()
                .addNode("boom", s -> { throw new IllegalStateException("kaboom"); })
                .addEdge(START, "boom").addEdge("boom", END)
                .compile();
        var ex = assertThrows(StateGraph.GraphExecutionException.class, () -> graph.invoke(new Counter()));
        assertEquals("boom", ex.node());
    }

    @Test
    void infiniteLoopIsStopped() {
        var graph = new StateGraph<Counter>()
                .addNode("spin", s -> { })
                .addEdge(START, "spin").addEdge("spin", "spin")
                .maxSteps(10)
                .compile();
        assertThrows(IllegalStateException.class, () -> graph.invoke(new Counter()));
    }

    @Test
    void invalidGraphsAreRejectedAtCompileTime() {
        assertThrows(IllegalStateException.class, () -> new StateGraph<Counter>()
                .addNode("a", s -> { }).compile());                       // no START edge
        assertThrows(IllegalStateException.class, () -> new StateGraph<Counter>()
                .addNode("a", s -> { }).addEdge(START, "a").compile());   // a has no outgoing edge
        assertThrows(IllegalStateException.class, () -> new StateGraph<Counter>()
                .addNode("a", s -> { }).addEdge(START, "a").addEdge("a", "ghost").compile());
    }

    @Test
    void mermaidExportMentionsLabelsAndNodes() {
        String mermaid = new StateGraph<Counter>()
                .addNode("a", s -> { })
                .addEdge(START, "a")
                .addConditionalEdges("a", s -> "x", Map.of("x", END))
                .toMermaid();
        assertTrue(mermaid.contains("a -->|x| END((end))"));
    }
}
