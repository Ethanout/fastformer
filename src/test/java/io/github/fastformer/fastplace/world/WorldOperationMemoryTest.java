package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldOperationMemoryTest {
   @Test
   void generationAdmissionDoesNotChargeFutureSnapshotPhase() {
      MemoryAdmission admission = WorldOperationMemory.admission(
         1L, 0L, 4L * 1024L * 1024L * 1024L,
         3L * 1024L * 1024L * 1024L, 256L * 1024L * 1024L
      );
      MemoryAdmission generation = WorldOperationMemory.generationAdmission(
         10_000_000L, 0L, 4L * 1024L * 1024L * 1024L,
         3L * 1024L * 1024L * 1024L, 256L * 1024L * 1024L
      );
      assertTrue(admission.allowed());
      assertTrue(generation.allowed());
      assertEquals(10_000_000L * 32L, generation.requestedBytes());
   }

   @Test
   void phaseAdmissionsChargeOnlyTheirLiveRepresentations() {
      long max = 8L * 1024L * 1024L * 1024L;
      long total = 2L * 1024L * 1024L * 1024L;
      long free = 1024L * 1024L * 1024L;

      MemoryAdmission generation = WorldOperationMemory.generationAdmission(1_000L, 1L, max, total, free);
      MemoryAdmission snapshots = WorldOperationMemory.snapshotAdmission(1_000L, 4_096L, max, total, free);
      MemoryAdmission transaction = WorldOperationMemory.transactionAdmission(1_000L, 4_096L);
      MemoryAdmission commit = WorldOperationMemory.commitAdmission(
         2_000L, 1_000L, 4_096L, max, total, free
      );
      MemoryAdmission journal = WorldOperationMemory.journalAdmission(1_000L, 4_096L, max, total, free);
      MemoryAdmission history = WorldOperationMemory.historyAdmission(12_345L, max, total, free);
      MemoryAdmission recovery = WorldOperationMemory.recoveryAdmission(
         12_345L, 1_000L, false, max, total, free
      );
      MemoryAdmission historyWithJournal = WorldOperationMemory.recoveryAdmission(
         12_345L, 1_000L, true, max, total, free
      );
      MemoryAdmission recoveryWorkingSet = WorldOperationMemory.recoveryWorkingSetAdmission(
         1_000L, false, max, total, free
      );
      MemoryAdmission recoveryWorkingSetWithJournal = WorldOperationMemory.recoveryWorkingSetAdmission(
         1_000L, true, max, total, free
      );

      assertEquals(1_000L * (32L + 64L), generation.requestedBytes());
      assertEquals(1_000L * 224L + 4_096L, snapshots.requestedBytes());
      assertEquals(snapshots.requestedBytes(), transaction.requestedBytes());
      assertEquals(2_000L * 224L + 1_000L * 96L + 4_096L, commit.requestedBytes());
      assertEquals(snapshots.requestedBytes() + 1_000L * 16L, journal.requestedBytes());
      assertEquals(12_345L, history.requestedBytes());
      assertEquals(12_345L + 1_000L, recovery.requestedBytes());
      assertEquals(recovery.requestedBytes() + 1_000L * 16L, historyWithJournal.requestedBytes());
      assertEquals(1_000L, recoveryWorkingSet.requestedBytes());
      assertEquals(1_000L + 1_000L * 16L, recoveryWorkingSetWithJournal.requestedBytes());
   }

   @Test
   void everyPhaseRejectsOverflowedEstimates() {
      long max = Long.MAX_VALUE;
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.generationAdmission(Long.MAX_VALUE, 1L, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.snapshotAdmission(Long.MAX_VALUE, 0L, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.journalAdmission(Long.MAX_VALUE, 0L, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.commitAdmission(Long.MAX_VALUE, 1L, 0L, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.historyAdmission(Long.MAX_VALUE, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.recoveryWorkingSetAdmission(Long.MAX_VALUE, true, max, 0L, max).status()
      );
      assertEquals(
         MemoryAdmissionStatus.HARD_REJECTED,
         WorldOperationMemory.recoveryWorkingSetAdmission(-1L, false, max, 0L, max).status()
      );
   }
}
