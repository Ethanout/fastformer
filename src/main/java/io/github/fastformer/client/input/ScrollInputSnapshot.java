package io.github.fastformer.client.input;

/** A scroll candidate captured at the physical callback boundary. */
record ScrollInputSnapshot(int direction, SelectionScrollMove selectionMove) {
   ScrollInputSnapshot(int direction) {
      this(direction, null);
   }
   ScrollInputSnapshot {
      if (direction == 0) throw new IllegalArgumentException("Scroll direction cannot be zero");
   }
}
