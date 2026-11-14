package io.github.fastformer.common.geometry.generation.volume.column;

/** The two fixed coordinates perpendicular to a column's varying axis. */
record ColumnKey(int first, int second) {
   ColumnKey neighbor(int firstDelta, int secondDelta) {
      long first = (long)this.first + firstDelta;
      long second = (long)this.second + secondDelta;
      if (first < Integer.MIN_VALUE || first > Integer.MAX_VALUE
         || second < Integer.MIN_VALUE || second > Integer.MAX_VALUE) {
         return null;
      }
      return new ColumnKey((int)first, (int)second);
   }
}
