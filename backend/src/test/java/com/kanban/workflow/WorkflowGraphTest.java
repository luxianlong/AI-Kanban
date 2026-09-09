package com.kanban.workflow;

import com.kanban.workflow.WorkflowGraph.Edge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide whether a workflow can run at all. They are pure functions
 * over ids, so this needs no Spring context — and they are worth pinning, because a
 * cycle that slips through would have the runner loop over the same nodes forever.
 */
class WorkflowGraphTest {

    private static Edge edge(String from, String to) {
        return new Edge(from, to);
    }

    @Test
    void aStraightChainIsValidAndRunsInOrder() {
        WorkflowGraph g = new WorkflowGraph(
                List.of("a", "b", "c"), List.of(edge("a", "b"), edge("b", "c")), "a");

        assertThat(g.validate()).isNull();
        assertThat(g.topologicalOrder()).containsExactly("a", "b", "c");
        assertThat(g.reachable()).containsExactly("a", "b", "c");
    }

    @Test
    void aDiamondRunsEveryNodeAfterWhatItDependsOn() {
        // a fans out to b and c, which both feed d — the shape that makes this a DAG
        // rather than a chain, and the reason a node can have two inputs.
        WorkflowGraph g = new WorkflowGraph(
                List.of("a", "b", "c", "d"),
                List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")),
                "a");

        assertThat(g.validate()).isNull();
        List<String> order = g.topologicalOrder();
        assertThat(order).hasSize(4);
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("d"));
        assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
        assertThat(g.dependenciesOf("d")).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void aCycleIsRejected() {
        WorkflowGraph g = new WorkflowGraph(
                List.of("a", "b", "c"),
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "b")),
                "a");

        assertThat(g.topologicalOrder()).isNull();
        assertThat(g.validate()).contains("cycle");
    }

    @Test
    void aNodeCannotFeedItself() {
        WorkflowGraph g = new WorkflowGraph(List.of("a"), List.of(edge("a", "a")), "a");

        assertThat(g.validate()).contains("cannot depend on itself");
    }

    @Test
    void anEdgeToAnUnknownNodeIsRejected() {
        WorkflowGraph g = new WorkflowGraph(List.of("a"), List.of(edge("a", "ghost")), "a");

        assertThat(g.validate()).contains("doesn't exist");
    }

    @Test
    void theEntryMustBeOneOfTheNodes() {
        assertThat(new WorkflowGraph(List.of("a"), List.of(), "b").validate())
                .contains("entry node");
        assertThat(new WorkflowGraph(List.of("a"), List.of(), null).validate())
                .contains("entry node");
    }

    @Test
    void anEmptyGraphIsRejected() {
        assertThat(new WorkflowGraph(List.of(), List.of(), null).validate())
                .contains("at least one node");
    }

    @Test
    void nodesTheEntryCannotReachAreReportedButDoNotInvalidateTheGraph() {
        // "orphan" is wired to nothing — the editor allows building a branch before
        // connecting it, so this is legal; it is simply never run.
        WorkflowGraph g = new WorkflowGraph(
                List.of("a", "b", "orphan"), List.of(edge("a", "b")), "a");

        assertThat(g.validate()).isNull();
        assertThat(g.reachable()).containsExactly("a", "b");
        assertThat(g.topologicalOrder()).contains("orphan");
    }

    @Test
    void theEntryIsInferredWhenExactlyOneNodeHasNoInput() {
        assertThat(WorkflowGraph.inferEntry(List.of("a", "b"), List.of(edge("a", "b")))).isEqualTo("a");
    }

    @Test
    void theEntryIsNotGuessedWhenSeveralNodesCouldBeIt() {
        // Two roots: picking one silently would run half the graph the user meant.
        assertThat(WorkflowGraph.inferEntry(List.of("a", "b", "c"), List.of(edge("a", "c"), edge("b", "c"))))
                .isNull();
        // No root at all (every node is fed) — a cycle; there is nothing to infer.
        assertThat(WorkflowGraph.inferEntry(List.of("a", "b"), List.of(edge("a", "b"), edge("b", "a"))))
                .isNull();
    }

    @Test
    void theRunOrderIsStableAcrossRunsOfTheSameGraph() {
        // Ties are broken by declaration order, so the same workflow always lists and
        // runs its nodes the same way rather than following hash iteration order.
        List<String> ids = List.of("a", "b", "c", "d");
        List<Edge> edges = List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d"));

        assertThat(new WorkflowGraph(ids, edges, "a").topologicalOrder())
                .isEqualTo(new WorkflowGraph(ids, edges, "a").topologicalOrder())
                .containsExactly("a", "b", "c", "d");
    }
}
