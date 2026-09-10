package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlacementTaskTest {
   @Test
   void readyTaskOwnsItsTargetCountAndDimension() {
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO, BlockPos.ZERO.above()),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.NORMAL, 100, Level.OVERWORLD
         )
      );

      assertTrue(task.prepare());
      assertEquals(2, task.total());
      assertEquals(0, task.processed());
      assertEquals(Level.OVERWORLD, task.dimension());
      assertFalse(task.exceededLimit());
      assertFalse(task.failed());
   }

   @Test
   void emptyReadyTaskHasNoWorldWork() {
      PlacementTask task = PlacementTask.ready(
         Set.of(),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.NORMAL, 100, Level.OVERWORLD
         )
      );

      assertTrue(task.prepare());
      assertEquals(0, task.total());
      task.cancel();
   }

   @Test
   void generatedTaskResolvesCompletedShapeInsideTheTaskObject() {
      PlacementTask task = PlacementTask.generating(
         CompletableFuture.completedFuture(Set.of(BlockPos.ZERO)),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 100, Level.NETHER
         )
      );

      assertTrue(task.prepare());
      assertEquals(1, task.total());
      assertEquals(Level.NETHER, task.dimension());
      assertFalse(task.generationConstraintsFailed());
   }

   @Test
   void completedGenerationReleasesItsWorkingSetReservation() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation generation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      PlacementTask task = PlacementTask.generating(
         CompletableFuture.completedFuture(Set.of(BlockPos.ZERO)),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 100, Level.OVERWORLD
         ),
         generation
      );

      assertTrue(MemoryReservation.reservedBytes() > baseline);
      assertTrue(task.prepare());
      task.cancel();
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void cancelledGenerationRetainsItsReservationUntilTheWorkerExits() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      long reserved = MemoryReservation.reservedBytes();
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      CompletableFuture<BlockGenerationResult> worker = CompletableFuture.supplyAsync(() -> {
         started.countDown();
         try {
            finish.await();
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
         }
         return BlockGenerationResult.fromLegacy(Set.of());
      });
      PlacementTask task = PlacementTask.generatingResult(
         worker,
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 100, Level.OVERWORLD
         ),
         reservation
      );

      try {
         assertTrue(started.await(5, TimeUnit.SECONDS));
         task.cancel();

         assertFalse(worker.isCancelled());
         assertEquals(reserved, MemoryReservation.reservedBytes());
         finish.countDown();
         worker.join();
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
         while (MemoryReservation.reservedBytes() != baseline && System.nanoTime() < deadline) {
            Thread.onSpinWait();
         }
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         finish.countDown();
         worker.join();
         reservation.close();
      }
   }

   @Test
   void successfulGenerationTransfersItsReservationToThePreparedTask() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      CompletableFuture<BlockGenerationResult> worker = new CompletableFuture<>();
      PlacementTask task = PlacementTask.generatingResult(
         worker,
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 100, Level.OVERWORLD
         ),
         reservation
      );

      try {
         worker.complete(BlockGenerationResult.fromLegacy(Set.of(BlockPos.ZERO)));
         assertTrue(task.prepare());

         assertTrue(MemoryReservation.reservedBytes() > baseline);
         task.cancel();
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         reservation.close();
      }
   }

   @Test
   void readyTaskCanReacquireItsReservationAfterWorldUnload() {
      long baseline = MemoryReservation.reservedBytes();
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO, BlockPos.ZERO.above()),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.NORMAL, 100, Level.OVERWORLD
         )
      );

      assertTrue(task.prepare());
      assertTrue(MemoryReservation.reservedBytes() > baseline);
      task.releaseMemoryReservation();
      assertEquals(baseline, MemoryReservation.reservedBytes());
      assertTrue(task.ensureMemoryReservation());
      assertTrue(MemoryReservation.reservedBytes() > baseline);
      task.cancel();
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void releasedGenerationStateExposesAnEmptyPositionIterator() {
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 100, Level.OVERWORLD
         )
      );

      assertTrue(task.prepare());
      task.releaseGenerationState();

      assertFalse(task.blocks().hasNext());
      task.cancel();
   }

   @Test
   void journalAfterViewMapsSnapshotsOnlyAsTheEncoderConsumesThem() {
      AtomicInteger mapped = new AtomicInteger();
      var view = PlacementTask.lazyMappedCollection(List.of(1, 2, 3), value -> {
         mapped.incrementAndGet();
         return value * 2;
      });

      assertEquals(3, view.size());
      assertEquals(0, mapped.get());
      assertEquals(List.of(2, 4, 6), List.copyOf(view));
      assertEquals(3, mapped.get());
   }

   @Test
   void stateResolverFailureBecomesAnExplicitGenerationFailure() {
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO),
         new PlacementTaskPlan(
            null,
            ignored -> { throw new IllegalStateException("resolver failed"); },
            OperationConflictMode.REPLACE,
            PlacementUpdateMode.CLIENT_ONLY,
            100,
            Level.OVERWORLD
         )
      );

      assertTrue(task.prepare());
      assertTrue(task.failed());
      assertEquals("state resolution: IllegalStateException", task.failureReason());
      assertEquals(WorldOperationPhase.GENERATION, task.failurePhase());
      assertEquals(0, task.total());
   }

   @Test
   void stateResolverOutOfMemoryIsReleasedAndReportedAsGenerationFailure() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      PlacementTask task = PlacementTask.generating(
         CompletableFuture.completedFuture(Set.of(BlockPos.ZERO)),
         new PlacementTaskPlan(
            null,
            ignored -> { throw new OutOfMemoryError("simulated"); },
            OperationConflictMode.REPLACE,
            PlacementUpdateMode.CLIENT_ONLY,
            100,
            Level.OVERWORLD
         ),
         reservation
      );

      assertTrue(task.prepare());
      assertTrue(task.failed());
      assertEquals("state resolution: OutOfMemoryError", task.failureReason());
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }
}
