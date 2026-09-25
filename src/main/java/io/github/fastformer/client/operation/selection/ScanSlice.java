package io.github.fastformer.client.operation.selection;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;

/** Applies both a cell bound and a cooperative time bound to world reads. */
public final class ScanSlice {
   private ScanSlice() {}
   public static int run(ScanCursor cursor, int limit, long deadline, LongSupplier clock, Consumer<BlockPos> read) {
      int scanned = 0;
      while (cursor.hasNext() && scanned < limit && clock.getAsLong() < deadline) {
         read.accept(cursor.next());
         scanned++;
      }
      return scanned;
   }
}
