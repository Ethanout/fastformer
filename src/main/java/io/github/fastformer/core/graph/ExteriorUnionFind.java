package io.github.fastformer.core.graph;

/** Union-find set that propagates whether a component touches the exterior. */
public final class ExteriorUnionFind {
   private final int[] parent;
   private final byte[] rank;
   private final boolean[] exterior;

   public ExteriorUnionFind(int size) {
      this.parent = new int[size];
      this.rank = new byte[size];
      this.exterior = new boolean[size];
      for (int index = 0; index < size; index++) {
         this.parent[index] = index;
      }
   }

   public void union(int first, int second) {
      int firstRoot = this.find(first);
      int secondRoot = this.find(second);
      if (firstRoot == secondRoot) {
         return;
      }
      if (this.rank[firstRoot] < this.rank[secondRoot]) {
         int swap = firstRoot;
         firstRoot = secondRoot;
         secondRoot = swap;
      }
      this.parent[secondRoot] = firstRoot;
      this.exterior[firstRoot] |= this.exterior[secondRoot];
      if (this.rank[firstRoot] == this.rank[secondRoot]) {
         this.rank[firstRoot]++;
      }
   }

   public void markExterior(int value) {
      this.exterior[this.find(value)] = true;
   }

   public boolean isExterior(int value) {
      return this.exterior[this.find(value)];
   }

   private int find(int value) {
      int root = value;
      while (this.parent[root] != root) {
         root = this.parent[root];
      }
      while (this.parent[value] != value) {
         int next = this.parent[value];
         this.parent[value] = root;
         value = next;
      }
      return root;
   }
}
