package com.kanban.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shape of a workflow, independent of how it is stored or run: which nodes
 * exist, which feed which, and where a run starts.
 *
 * <p>Deliberately built from plain ids rather than JPA entities so the rules that
 * actually matter — a single entry, no cycles, no dangling edges — are pure
 * functions that can be tested without a database (see {@code WorkflowGraphTest}).
 */
public final class WorkflowGraph {

    /** One directed dependency: {@code from}'s output feeds {@code to}'s input. */
    public record Edge(String from, String to) {
    }

    private final List<String> nodeIds;
    private final List<Edge> edges;
    private final String entryNodeId;
    private final Map<String, List<String>> incoming = new HashMap<>();
    private final Map<String, List<String>> outgoing = new HashMap<>();

    public WorkflowGraph(List<String> nodeIds, List<Edge> edges, String entryNodeId) {
        this.nodeIds = List.copyOf(nodeIds);
        this.edges = List.copyOf(edges);
        this.entryNodeId = entryNodeId;
        for (String id : this.nodeIds) {
            incoming.put(id, new ArrayList<>());
            outgoing.put(id, new ArrayList<>());
        }
        for (Edge e : this.edges) {
            if (incoming.containsKey(e.to()) && outgoing.containsKey(e.from())) {
                incoming.get(e.to()).add(e.from());
                outgoing.get(e.from()).add(e.to());
            }
        }
    }

    public List<String> nodeIds() {
        return nodeIds;
    }

    public String entryNodeId() {
        return entryNodeId;
    }

    /** The nodes whose output feeds {@code nodeId}, in the order the edges were given. */
    public List<String> dependenciesOf(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    /**
     * Check the graph is runnable, returning the first problem found or {@code null}
     * when it is fine. The rules:
     *
     * <ul>
     *   <li>at least one node, each with a unique id;
     *   <li>every edge connects two nodes that exist, and no node feeds itself;
     *   <li>the entry node exists (and, when the caller left it unset, can be
     *       inferred — see {@link #inferEntry});
     *   <li>the graph is acyclic, so a run always terminates.
     * </ul>
     *
     * <p>Nodes unreachable from the entry are <em>not</em> an error — the editor lets
     * a branch be built up before it is wired in. They are recorded as
     * {@link RunState#SKIPPED} when an execution is created, so their absence from a
     * run is visible rather than silent.
     */
    public String validate() {
        if (nodeIds.isEmpty()) {
            return "A workflow needs at least one node.";
        }
        if (new HashSet<>(nodeIds).size() != nodeIds.size()) {
            return "Two nodes share the same id.";
        }
        Set<String> known = new HashSet<>(nodeIds);
        for (Edge e : edges) {
            if (!known.contains(e.from()) || !known.contains(e.to())) {
                return "Edge " + e.from() + " → " + e.to() + " refers to a node that doesn't exist.";
            }
            if (e.from().equals(e.to())) {
                return "A node cannot depend on itself (" + e.from() + ").";
            }
        }
        if (entryNodeId == null || !known.contains(entryNodeId)) {
            return "The entry node must be one of the workflow's nodes.";
        }
        if (topologicalOrder() == null) {
            return "The nodes form a cycle — a workflow must be a DAG.";
        }
        return null;
    }

    /**
     * Kahn's algorithm: every node in an order where each comes after everything it
     * depends on. Returns {@code null} if the graph has a cycle.
     *
     * <p>Ties are broken by the node's position in {@link #nodeIds} so the order — and
     * therefore the order nodes are listed and run in — is stable across runs of the
     * same workflow rather than dependent on hash iteration.
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            position.put(nodeIds.get(i), i);
        }
        Map<String, Integer> remaining = new HashMap<>();
        for (String id : nodeIds) {
            remaining.put(id, dependenciesOf(id).size());
        }
        // A priority queue keeps the ready set in declaration order at every step.
        List<String> ready = new ArrayList<>();
        for (String id : nodeIds) {
            if (remaining.get(id) == 0) {
                ready.add(id);
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            ready.sort(Comparator.comparingInt(position::get));
            String next = ready.remove(0);
            order.add(next);
            for (String downstream : outgoing.getOrDefault(next, List.of())) {
                int left = remaining.merge(downstream, -1, Integer::sum);
                if (left == 0) {
                    ready.add(downstream);
                }
            }
        }
        return order.size() == nodeIds.size() ? order : null;
    }

    /** Every node an execution can actually reach by following edges from the entry. */
    public Set<String> reachable() {
        Set<String> seen = new LinkedHashSet<>();
        if (entryNodeId == null || !outgoing.containsKey(entryNodeId)) {
            return seen;
        }
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entryNodeId);
        seen.add(entryNodeId);
        while (!queue.isEmpty()) {
            for (String next : outgoing.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /**
     * The node a run should start from when the client didn't name one: the single
     * node nothing feeds into. Returns {@code null} if there is no such node or more
     * than one, in which case the client has to choose — a workflow has exactly one
     * entry, and guessing between candidates would silently run the wrong branch.
     */
    public static String inferEntry(List<String> nodeIds, List<Edge> edges) {
        Set<String> hasIncoming = new HashSet<>();
        for (Edge e : edges) {
            hasIncoming.add(e.to());
        }
        String candidate = null;
        for (String id : nodeIds) {
            if (!hasIncoming.contains(id)) {
                if (candidate != null) {
                    return null;
                }
                candidate = id;
            }
        }
        return candidate;
    }
}
