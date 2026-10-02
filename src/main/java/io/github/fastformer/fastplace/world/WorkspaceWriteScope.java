package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Attributes synchronous block callbacks to the workspace write that caused them. */
public final class WorkspaceWriteScope implements AutoCloseable {
   private static final ThreadLocal<WorkspaceWriteScope> ACTIVE = new ThreadLocal<>();
   private final WorkspaceWriteScope previous;
   private final ServerLevel level;
   private final WorldChangeTransaction transaction;
   private final Set<BlockPos> touched = new HashSet<>();
   private BlockPos conflict;

   public WorkspaceWriteScope(ServerLevel level, WorldChangeTransaction transaction) {
      this.previous = ACTIVE.get();
      this.level = level;
      this.transaction = transaction;
      ACTIVE.set(this);
   }

   public BlockPos conflict() { return conflict; }

   public static boolean observe(Level level, BlockPos pos, BooleanSupplier write) {
      var scope = ACTIVE.get();
      if (scope == null || scope.level != level || !scope.transaction.expects(pos)) return write.getAsBoolean();
      BlockPos position = pos.immutable();
      if (scope.touched.add(position)) {
         var expected = scope.transaction.expectedAt(position);
         if (!expected.matches(scope.level, position)) {
            scope.conflict = position;
            return false;
         }
         scope.transaction.recordBeforeOnce(expected);
      }
      try { return write.getAsBoolean(); }
      finally {
         var actual = ReversibleBlockSnapshot.capture(scope.level, position);
         if (actual.isEmpty()) scope.conflict = position;
         else {
            scope.transaction.recordExpected(position, actual.orElseThrow());
            scope.transaction.recordAfter(position, actual.orElseThrow());
         }
      }
   }

   @Override public void close() {
      if (previous == null) ACTIVE.remove();
      else ACTIVE.set(previous);
   }
}
