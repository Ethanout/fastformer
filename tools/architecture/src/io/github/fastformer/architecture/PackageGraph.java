package io.github.fastformer.architecture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reports every directed edge inside a strongly connected package component. */
final class PackageGraph {
    private final Map<String, Set<String>> edges;
    private final Map<String, Integer> index = new HashMap<>();
    private final Map<String, Integer> low = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> stacked = new HashSet<>();
    private final List<Set<String>> components = new ArrayList<>();

    private PackageGraph(Map<String, Set<String>> edges) { this.edges = edges; }

    static List<Set<String>> components(Map<String, Set<String>> edges) {
        PackageGraph graph = new PackageGraph(edges);
        Set<String> vertices = new TreeSet<>(edges.keySet());
        edges.values().forEach(vertices::addAll);
        for (String vertex : vertices) if (!graph.index.containsKey(vertex)) graph.visit(vertex);
        return List.copyOf(graph.components);
    }

    static Map<String, Set<String>> fromClasses(Map<String, Set<String>> classes, String prefix) {
        Map<String, Set<String>> result = new TreeMap<>();
        classes.forEach((owner, targets) -> {
            if (!owner.startsWith(prefix)) return;
            String origin = packageName(owner);
            result.computeIfAbsent(origin, ignored -> new TreeSet<>());
            for (String target : targets) {
                String destination = packageName(target);
                if (target.startsWith(prefix) && !destination.equals(origin)) result.get(origin).add(destination);
            }
        });
        return result;
    }

    static String packageName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? "" : type.substring(0, dot);
    }

    private void visit(String vertex) {
        int current = index.size();
        index.put(vertex, current);
        low.put(vertex, current);
        stack.push(vertex);
        stacked.add(vertex);
        for (String next : edges.getOrDefault(vertex, Set.of())) {
            if (!index.containsKey(next)) {
                visit(next);
                low.put(vertex, Math.min(low.get(vertex), low.get(next)));
            } else if (stacked.contains(next)) low.put(vertex, Math.min(low.get(vertex), index.get(next)));
        }
        if (!low.get(vertex).equals(index.get(vertex))) return;
        Set<String> component = new TreeSet<>();
        String next;
        do {
            next = stack.pop();
            stacked.remove(next);
            component.add(next);
        } while (!next.equals(vertex));
        components.add(component);
    }
}
