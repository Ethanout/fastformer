package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.world.MemoryAdmission;
import io.github.fastformer.fastplace.world.MemoryAdmissionStatus;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlacementDeferredGenerationTest {
   @Test
   void contentionWaitsThenStartsGeneratorOnce() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      MemoryAdmission admission = WorldOperationMemory.generationAdmission(1L, 0L);
      MemoryReservation blocker = fillReservationLimit(admission);
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredTask(() -> {
         starts.incrementAndGet();
         return success();
      }, 1L);
      try {
         assertFalse(task.prepare());
         assertFalse(task.prepare());
         assertTrue(task.waitingForGenerationMemory());
         assertEquals(0, starts.get());

         blocker.close();
         awaitPrepared(task);
         assertEquals(1, starts.get());
         assertFalse(task.waitingForGenerationMemory());
      } finally {
         blocker.close();
         task.cancel();
      }
      awaitReservedBytes(baseline);
   }

   @Test
   void cancellationWhileWaitingNeverStartsGenerator() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryAdmission admission = WorldOperationMemory.generationAdmission(1L, 0L);
      MemoryReservation blocker = fillReservationLimit(admission);
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredTask(() -> {
         starts.incrementAndGet();
         return success();
      }, 1L);
      try {
         assertFalse(task.prepare());
         task.cancel();
         blocker.close();

         assertFalse(task.waitingForGenerationMemory());
         assertEquals(0, starts.get());
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         blocker.close();
         task.cancel();
      }
   }

   @Test
   void cancellationAfterStartRetainsReservationUntilWorkerExits() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      PlacementTask task = deferredTask(() -> {
         started.countDown();
         await(finish);
         return success();
      }, 1L);
      try {
         assertFalse(task.prepare());
         assertTrue(started.await(5, TimeUnit.SECONDS));
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         task.cancel();
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         finish.countDown();
         awaitReservedBytes(baseline);
      } finally {
         finish.countDown();
         task.cancel();
      }
   }

   @Test
   void hardAdmissionRejectsWithoutStartingGenerator() {
      long baseline = MemoryReservation.reservedBytes();
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredTask(() -> {
         starts.incrementAndGet();
         return success();
      }, Long.MAX_VALUE);

      assertTrue(task.prepare());
      assertTrue(task.memoryUnsafe());
      assertFalse(task.waitingForGenerationMemory());
      assertEquals(0, starts.get());
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   private static PlacementTask deferredTask(java.util.function.Supplier<BlockGenerationResult> generator, long estimate) {
      return PlacementTask.waitingForGeneration(generator, null, new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ), estimate, 0L);
   }

   private static BlockGenerationResult success() {
      return new BlockGenerationResult(BlockGenerationResult.Status.SUCCESS, Set.of(BlockPos.ZERO));
   }

   private static MemoryReservation fillReservationLimit(MemoryAdmission target) {
      assertTrue(target.allowed());
      long remaining = target.usableBytes() - MemoryReservation.reservedBytes();
      return WorldOperationMemory.reserve(new MemoryAdmission(
         MemoryAdmissionStatus.ALLOWED, remaining, target.usableBytes()
      )).orElseThrow();
   }

   private static void awaitPrepared(PlacementTask task) throws Exception {
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
         while (!task.prepare()) {
            Thread.onSpinWait();
         }
      });
   }

   private static void awaitReservedBytes(long expected) throws Exception {
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
         while (MemoryReservation.reservedBytes() != expected) {
            Thread.onSpinWait();
         }
      });
   }

   private static void await(CountDownLatch latch) {
      try {
         latch.await();
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException(exception);
      }
   }
}
