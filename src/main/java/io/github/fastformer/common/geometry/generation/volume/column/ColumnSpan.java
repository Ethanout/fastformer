package io.github.fastformer.common.geometry.generation.volume.column;

/** Inclusive occupied interval on a column's varying axis. */
record ColumnSpan(int minimum, int maximum) {
   ColumnSpan include(int coordinate) {
      return new ColumnSpan(Math.min(this.minimum, coordinate), Math.max(this.maximum, coordinate));
   }

   boolean contains(long coordinate) {
      return coordinate >= this.minimum && coordinate <= this.maximum;
   }
}
