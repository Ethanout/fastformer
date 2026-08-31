package io.github.fastformer.fastplace.geometry.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

final class FlowGraph {
   private static final long INFINITE_CAPACITY = Long.MAX_VALUE / 16L;
   private final ArrayList<ArrayList<Edge>> adjacency = new ArrayList<>();
   private int[] level = new int[0];
   private int[] next = new int[0];
   private int[] pathNodes = new int[0];
   private int[] pathEdges = new int[0];
   private long[] pathAmounts = new long[0];

   int addNode() {
      this.adjacency.add(new ArrayList<>());
      return this.adjacency.size() - 1;
   }

   void addEdge(int from, int to, long capacity) {
      Edge forward = new Edge(to, this.adjacency.get(to).size(), capacity);
      Edge reverse = new Edge(from, this.adjacency.get(from).size(), 0L);
      this.adjacency.get(from).add(forward);
      this.adjacency.get(to).add(reverse);
   }

   long maximumFlow(int source, int sink, BlockGenerationObserver observer) {
      long result = 0L;
      while (this.buildLevels(source, sink, observer)) {
         this.next = new int[this.adjacency.size()];
         this.pathNodes = new int[this.adjacency.size()];
         this.pathEdges = new int[this.adjacency.size()];
         this.pathAmounts = new long[this.adjacency.size()];
         long pushed;
         while ((pushed = this.pushIterative(source, sink, INFINITE_CAPACITY, observer)) > 0L) {
            result = Math.min(INFINITE_CAPACITY, result + pushed);
         }
      }
      return result;
   }

   boolean[] reachableFrom(int source) {
      boolean[] seen = new boolean[this.adjacency.size()];
      ArrayDeque<Integer> open = new ArrayDeque<>();
      seen[source] = true;
      open.add(source);
      while (!open.isEmpty()) {
         int current = open.removeFirst();
         for (Edge edge : this.adjacency.get(current)) {
            if (edge.capacity > 0L && !seen[edge.to]) {
               seen[edge.to] = true;
               open.addLast(edge.to);
            }
         }
      }
      return seen;
   }

   private boolean buildLevels(int source, int sink, BlockGenerationObserver observer) {
      this.level = new int[this.adjacency.size()];
      Arrays.fill(this.level, -1);
      ArrayDeque<Integer> open = new ArrayDeque<>();
      this.level[source] = 0;
      open.add(source);
      int visited = 0;
      while (!open.isEmpty()) {
         if ((visited++ & 1023) == 0) {
            observer.checkCancelled();
         }
         int current = open.removeFirst();
         for (Edge edge : this.adjacency.get(current)) {
            if (edge.capacity > 0L && this.level[edge.to] < 0) {
               this.level[edge.to] = this.level[current] + 1;
               open.addLast(edge.to);
            }
         }
      }
      return this.level[sink] >= 0;
   }

   private long pushIterative(
      int source,
      int sink,
      long amount,
      BlockGenerationObserver observer
   ) {
      int depth = 0;
      int visited = 0;
      this.pathNodes[0] = source;
      this.pathAmounts[0] = amount;
      while (depth >= 0) {
         if ((visited++ & 4095) == 0) {
            observer.checkCancelled();
         }
         int current = this.pathNodes[depth];
         if (current == sink) {
            long pushed = this.pathAmounts[depth];
            for (int index = 0; index < depth; index++) {
               int from = this.pathNodes[index];
               Edge edge = this.adjacency.get(from).get(this.pathEdges[index]);
               edge.capacity -= pushed;
               this.adjacency.get(edge.to).get(edge.reverse).capacity += pushed;
            }
            return pushed;
         }

         ArrayList<Edge> edges = this.adjacency.get(current);
         boolean advanced = false;
         while (this.next[current] < edges.size()) {
            int edgeIndex = this.next[current];
            Edge edge = edges.get(edgeIndex);
            if (edge.capacity > 0L && this.level[edge.to] == this.level[current] + 1) {
               this.pathEdges[depth] = edgeIndex;
               this.pathNodes[depth + 1] = edge.to;
               this.pathAmounts[depth + 1] = Math.min(this.pathAmounts[depth], edge.capacity);
               depth++;
               advanced = true;
               break;
            }
            this.next[current]++;
         }
         if (advanced) {
            continue;
         }
         this.level[current] = -1;
         if (depth == 0) {
            return 0L;
         }
         depth--;
         this.next[this.pathNodes[depth]]++;
      }
      return 0L;
   }

   private static final class Edge {
      private final int to;
      private final int reverse;
      private long capacity;

      Edge(int to, int reverse, long capacity) {
         this.to = to;
         this.reverse = reverse;
         this.capacity = capacity;
      }
   }
}
