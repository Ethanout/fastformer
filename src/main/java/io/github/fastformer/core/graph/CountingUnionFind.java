package io.github.fastformer.core.graph;

/** Union-find set that also tracks the number of remaining components. */
public final class CountingUnionFind {
   private final int[] parent;
   private final byte[] rank;
   private int count;

   public CountingUnionFind(int size) {
      this.parent = new int[size];
      this.rank = new byte[size];
      this.count = size;
      for (int index = 0; index < size; index++) {
         this.parent[index] = index;
      }
   }

   public int find(int value) {
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

   public void union(int first, int second) {
      int firstRoot = find(first);
      int secondRoot = find(second);
      if (firstRoot == secondRoot) {
         return;
      }
      if (this.rank[firstRoot] < this.rank[secondRoot]) {
         this.parent[firstRoot] = secondRoot;
      } else {
         this.parent[secondRoot] = firstRoot;
         if (this.rank[firstRoot] == this.rank[secondRoot]) {
            this.rank[firstRoot]++;
         }
      }
      this.count--;
   }

   public int count() {
      return this.count;
   }
}
