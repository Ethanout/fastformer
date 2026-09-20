package io.github.fastformer.fastplace.selection;


import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OperationStackRegionTest {
   @Test
   void enumerationStopsAtTheLargestIntegerEndpoint() {
      for (BlockPos endpoint : List.of(
         new BlockPos(Integer.MAX_VALUE, 0, 0),
         new BlockPos(0, Integer.MAX_VALUE, 0),
         new BlockPos(0, 0, Integer.MAX_VALUE))) {
         OperationStackRegion region = new OperationStackRegion(endpoint, endpoint);
         assertEquals(List.of(endpoint), region.repetitions(2));
      }
   }

   @Test
   void originContainsExactlyOneCell() {
      OperationStackRegion region = OperationStackRegion.origin();

      assertEquals(BlockPos.ZERO, region.min());
      assertEquals(BlockPos.ZERO, region.max());
      assertEquals(1L, region.cellCount());
      assertEquals(List.of(BlockPos.ZERO), region.repetitions(10));
   }

   @Test
   void repeatingAlongTheSameAxisAddsCopiesLinearly() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1);

      assertEquals(new BlockPos(0, 0, 0), region.min());
      assertEquals(new BlockPos(4, 0, 0), region.max());
      assertEquals(5L, region.cellCount());
   }

   @Test
   void negativeStackExpandsTheMinimumEndpoint() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.Z, -1, 2);

      assertEquals(new BlockPos(0, 0, -2), region.min());
      assertEquals(BlockPos.ZERO, region.max());
   }

   @Test
   void reverseRepeatRetractsOnlyTheDraggedSideAndNeverFlipsTheFace() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, -1, 2);

      OperationStackRegion positiveRetracted = region.withAxisEndpoint(AxisGizmo.Axis.X, 1, -9);
      OperationStackRegion negativeRetracted = region.withAxisEndpoint(AxisGizmo.Axis.X, -1, -1);

      assertEquals(new BlockPos(-2, 0, 0), positiveRetracted.min());
      assertEquals(BlockPos.ZERO, positiveRetracted.max(), "the +X handle stops at the origin instead of becoming -X");
      assertEquals(new BlockPos(-1, 0, 0), negativeRetracted.min());
      assertEquals(new BlockPos(3, 0, 0), negativeRetracted.max());
   }

   @Test
   void axesFormACartesianProduct() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 1)
         .repeat(AxisGizmo.Axis.Y, -1, 2)
         .repeat(AxisGizmo.Axis.Z, 1, 3);

      assertEquals(24L, region.cellCount());
      assertEquals(24, region.repetitions(100).size());
   }

   @Test
   void onlyRepeatsOnDifferentAxesMultiplyTheCurrentRegion() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1)
         .repeat(AxisGizmo.Axis.Y, 1, 1);

      assertEquals(10L, region.cellCount());
   }

   @Test
   void zeroCopiesPreservesTheSameImmutableValue() {
      OperationStackRegion origin = OperationStackRegion.origin();

      assertSame(origin, origin.repeat(AxisGizmo.Axis.X, 1, 0));
   }

   @Test
   void endpointsAndEnumerationAreBoundedByOperationLimit() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Y, -1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Z, 1, Integer.MAX_VALUE);

      assertEquals(new BlockPos(0, -128, 0), region.min());
      assertEquals(new BlockPos(128, 0, 128), region.max());
      assertEquals(129L * 129L * 129L, region.cellCount());
      assertEquals(7, region.repetitions(7).size());
   }

   /** The largest region the repeat gesture can build: 129 cells on each axis. */
   private static OperationStackRegion limitSizedRegion() {
      return OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Y, -1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Z, 1, Integer.MAX_VALUE);
   }

   @Test
   void aLargeRegionDeliversItsFirstCellWithoutBuildingTheWholeList() {
      OperationStackRegion region = limitSizedRegion();
      List<BlockPos> delivered = new ArrayList<>();

      long visited = region.visitRepetitions(1, position -> {
         delivered.add(position);
         return true;
      });

      assertEquals(1L, visited);
      assertEquals(List.of(new BlockPos(0, -128, 0)), delivered);
      // The region is far larger than the one cell the call asked for.
      assertEquals(129L * 129L * 129L, region.cellCount());
   }

   @Test
   void aVisitorThatStopsEndsTheWalkImmediately() {
      OperationStackRegion region = limitSizedRegion();
      AtomicLong calls = new AtomicLong();

      long visited = region.visitRepetitions(Long.MAX_VALUE, position -> {
         calls.incrementAndGet();
         return false;
      });

      assertEquals(1L, visited, "a stopping visitor still counts the cell it received");
      assertEquals(1L, calls.get(), "the walk continued after the visitor stopped it");
   }

   @Test
   void theVisitorOrderMatchesTheLegacyList() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 1)
         .repeat(AxisGizmo.Axis.Y, -1, 2)
         .repeat(AxisGizmo.Axis.Z, 1, 2);
      List<BlockPos> delivered = new ArrayList<>();

      long visited = region.visitRepetitions(100, position -> {
         delivered.add(position);
         return true;
      });

      assertEquals(region.cellCount(), visited);
      assertEquals(region.repetitions(100), delivered);
      assertEquals(region.repetitions(5), delivered.subList(0, 5), "the bounded walk truncated a different prefix");
   }

   @Test
   void aBoundAtOrBelowZeroDeliversNothing() {
      OperationStackRegion region = limitSizedRegion();

      assertEquals(0L, region.visitRepetitions(0, position -> true));
      assertEquals(0L, region.visitRepetitions(-1, position -> true));
      assertEquals(List.of(), region.repetitions(0));
   }

   @Test
   void anExtremeEndpointRegionWalksWithoutOverflow() {
      OperationStackRegion region = new OperationStackRegion(
         new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)
      );
      List<BlockPos> delivered = new ArrayList<>();

      long visited = region.visitRepetitions(3, position -> {
         delivered.add(position);
         return true;
      });

      assertEquals(4294967296L, region.cellCount());
      assertEquals(3L, visited);
      assertEquals(List.of(
         new BlockPos(Integer.MIN_VALUE, 0, 0),
         new BlockPos(Integer.MIN_VALUE + 1, 0, 0),
         new BlockPos(Integer.MIN_VALUE + 2, 0, 0)
      ), delivered);
   }
}
