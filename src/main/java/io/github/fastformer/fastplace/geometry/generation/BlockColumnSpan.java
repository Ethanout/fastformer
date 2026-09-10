package io.github.fastformer.fastplace.geometry.generation;

record BlockColumnSpan(int min, int max) {
   static final BlockColumnSpan EMPTY = new BlockColumnSpan(Integer.MAX_VALUE, Integer.MIN_VALUE);

   boolean empty() {
      return this.min > this.max;
   }

   long length() {
      return empty() ? 0L : (long)this.max - this.min + 1L;
   }

   boolean contains(int value) {
      return !empty() && value >= this.min && value <= this.max;
   }
}
