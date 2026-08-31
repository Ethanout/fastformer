package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlacementTaskTest {
   @Test
   void readyTaskOwnsItsTargetCountAndDimension() {
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO, BlockPos.ZERO.above()),
         null,
         OperationConflictMode.REPLACE,
         PlacementUpdateMode.NORMAL,
         100,
         Level.OVERWORLD
      );

      assertTrue(task.prepare());
      assertEquals(2, task.total());
      assertEquals(0, task.processed());
      assertEquals(Level.OVERWORLD, task.dimension());
      assertFalse(task.exceededLimit());
      assertFalse(task.failed());
   }

   @Test
   void generatedTaskResolvesCompletedShapeInsideTheTaskObject() {
      PlacementTask task = PlacementTask.generating(
         CompletableFuture.completedFuture(Set.of(BlockPos.ZERO)),
         null,
         null,
         OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY,
         100,
         Level.NETHER
      );

      assertTrue(task.prepare());
      assertEquals(1, task.total());
      assertEquals(Level.NETHER, task.dimension());
      assertFalse(task.generationConstraintsFailed());
   }
}
