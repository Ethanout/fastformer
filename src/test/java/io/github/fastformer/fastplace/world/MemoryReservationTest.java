package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class MemoryReservationTest {
   @Test
   void placementWaitsForTemporaryReservationContentionAndCanRetry() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryAdmission admission = WorldOperationMemory.snapshotAdmission(1L, 0L);
      MemoryReservation blocker = MemoryReservation.tryAcquire(
         admission.usableBytes() - baseline,
         admission.usableBytes()
      ).orElseThrow();
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.NORMAL, 1, Level.OVERWORLD
         )
      );
      try {
         assertTrue(task.prepare());
         assertFalse(task.memoryUnsafe());
         assertFalse(task.ensureMemoryReservation());

         blocker.close();
         assertTrue(task.ensureMemoryReservation());
         assertFalse(task.memoryUnsafe());
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void reservesResizesAndReleasesExactlyOnce() {
      long baseline = MemoryReservation.reservedBytes();
      long limit = baseline + 100L;
      MemoryReservation reservation = MemoryReservation.tryAcquire(60L, limit).orElseThrow();
      assertEquals(60L, reservation.bytes());
      assertFalse(MemoryReservation.tryAcquire(50L, limit).isPresent());

      assertTrue(reservation.resize(80L, limit));
      assertEquals(80L, reservation.bytes());
      reservation.close();
      reservation.close();
      assertEquals(0L, reservation.bytes());
      MemoryReservation replacement = MemoryReservation.tryAcquire(100L, limit).orElseThrow();
      replacement.close();
   }

   @Test
   void closedReservationCannotGrow() {
      long baseline = MemoryReservation.reservedBytes();
      long limit = baseline + 100L;
      MemoryReservation reservation = MemoryReservation.tryAcquire(10L, limit).orElseThrow();
      reservation.close();
      assertFalse(reservation.resize(20L, limit));
   }

   @Test
   void resizeRejectsGrowthBeyondUsableBudget() {
      long baseline = MemoryReservation.reservedBytes();
      long limit = baseline + 1L;
      MemoryReservation reservation = MemoryReservation.tryAcquire(1L, limit).orElseThrow();
      assertFalse(reservation.resize(Long.MAX_VALUE, limit));
      assertEquals(1L, reservation.bytes());
      reservation.close();
   }

   @Test
   void generationReservationParticipatesInGlobalAccounting() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      try {
         assertTrue(MemoryReservation.reservedBytes() >= baseline + reservation.bytes());
      } finally {
         reservation.close();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }
}
