package io.github.fastformer.server.task.block;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.common.world.memory.MemoryAdmission;
import io.github.fastformer.common.world.memory.MemoryAdmissionStatus;
import io.github.fastformer.common.world.memory.MemoryReservation;
import io.github.fastformer.common.world.memory.WorldOperationMemory;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.task.MemoryReservationAttempt;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.runtime.TaskRuntimeFixtures;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.BlockSnapshotReader;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.worldwrite.snapshot.SnapshotMemoryRules;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class IncrementalMemoryContentionTest {
   @Test void workspaceRetriesCapturedSnapshotGrowthWithoutCountingItTwice() {
      long baseline = MemoryReservation.reservedBytes();
      var capture = new WorkspaceSnapshotCapture(Collections.singletonMap(BlockPos.ZERO, null));
      var task = workspaceTask(capture, BlockTaskMemoryPolicy.standard());
      var captured = largeSnapshot();
      long nbt = SnapshotMemoryRules.nbtReserve(captured);
      AtomicInteger reads = new AtomicInteger();
      MemoryReservation[] blocker = new MemoryReservation[1];
      BlockSnapshotReader reader = (level, pos) -> {
         reads.incrementAndGet();
         blocker[0] = fillReservationLimit(WorldOperationMemory.snapshotAdmission(1L, nbt));
         return Optional.of(captured);
      };
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      try {
         assertEquals(WorkspaceSnapshotCapture.Step.RETRY, capture.next(null, task.transaction(), reader,
            (position, target) -> true, task::reserveMemory, () -> {}));
         assertEquals(1, task.transaction().expectedCount());
         assertEquals(nbt, capture.nbtReserve());
         blocker[0].close();
         assertEquals(WorkspaceSnapshotCapture.Step.ADVANCED, capture.next(null, task.transaction(), reader,
            (position, target) -> true, task::reserveMemory, () -> {}));
         assertEquals(1, reads.get());
         assertEquals(1, task.transaction().expectedCount());
         assertEquals(nbt, capture.nbtReserve());
         assertTrue(capture.complete());
      } finally {
         if (blocker[0] != null) blocker[0].close();
         task.releaseMemoryReservation();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test void selectionRetriesCompletedScanWithoutRepeatingSnapshotState() {
      long baseline = MemoryReservation.reservedBytes();
      var captured = largeSnapshot();
      AtomicInteger reads = new AtomicInteger();
      MemoryReservation[] blocker = new MemoryReservation[1];
      BlockSnapshotReader reader = nonAirReader((level, pos) -> {
         reads.incrementAndGet();
         blocker[0] = fillReservationLimit(WorldOperationMemory.snapshotAdmission(1L, SnapshotMemoryRules.nbtReserve(captured)));
         return Optional.of(captured);
      });
      SelectionOperationTask task = selectionTask(reader, BlockTaskMemoryPolicy.standard());
      try {
         assertEquals(TaskStep.ACTIVE, task.scanNext(null).orElseThrow());
         assertEquals("扫描", task.phaseName());
         assertEquals(1, task.transaction().expectedCount());
         blocker[0].close();
         assertTrue(task.finishScan().isEmpty());
         assertEquals("写入安全日志", task.phaseName());
         assertEquals(1, reads.get());
         assertEquals(1, task.transaction().expectedCount());
      } finally {
         if (blocker[0] != null) blocker[0].close();
         task.releaseMemoryReservation();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test void hardAdmissionRejectsWorkspaceAndSelectionProgress() {
      var capture = new WorkspaceSnapshotCapture(Collections.singletonMap(BlockPos.ZERO, null));
      var workspace = workspaceTask(capture, rejected());
      assertEquals(WorkspaceSnapshotCapture.Step.REJECTED, capture.next(null, workspace.transaction(),
         BlockSnapshotReader.live(), (position, target) -> true, workspace::reserveMemory, () -> {}));
      var selection = selectionTask(nonAirReader((level, pos) -> Optional.empty()), rejected());
      assertEquals(TaskStep.MEMORY_UNSAFE, selection.scanNext(null).orElseThrow());
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(workspace));
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(selection));
   }

   @Test void placementCommitRetriesThenAdvancesAndReleasesReservation() {
      long baseline = MemoryReservation.reservedBytes();
      PlacementTask task = placementTask(BlockTaskMemoryPolicy.standard());
      assertTrue(task.prepare());
      prepareChangedTransaction(task.transaction());
      task.releaseGenerationState();
      task.resizeMemoryReservationForTransaction();
      assertFalse(task.blocks().hasNext());
      assertEquals(0, task.transaction().expectedCount());
      MemoryReservation blocker = fillReservationLimit(commitAdmission(task.transaction()));
      try {
         assertEquals(JournalPreparation.PENDING, task.prepareCommit());
         assertNull(task.operationCommit());
         blocker.close();
         task.prepareCommit();
         task.operationCommit().completion().join();
         assertEquals(JournalPreparation.READY, task.prepareCommit());
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
      } finally { blocker.close(); task.releaseMemoryReservation(); }
      assertReleased(task, baseline);
   }

   @Test void workspaceCommitRetriesThenAdvancesAndReleasesReservation() {
      long baseline = MemoryReservation.reservedBytes();
      var capture = new WorkspaceSnapshotCapture(Collections.singletonMap(BlockPos.ZERO, null));
      var task = workspaceTask(capture, BlockTaskMemoryPolicy.standard());
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      prepareChangedTransaction(task.transaction());
      task.releaseWriteStagingForCommit();
      assertTrue(capture.desired().isEmpty());
      assertEquals(0, task.transaction().expectedCount());
      MemoryReservation blocker = fillReservationLimit(commitAdmission(task.transaction()));
      try {
         assertEquals(TaskStep.ACTIVE, task.prepareCommit());
         assertNull(task.operationCommit());
         blocker.close();
         task.prepareCommit();
         task.operationCommit().completion().join();
         assertEquals(TaskStep.COMPLETE, task.prepareCommit());
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
      } finally { blocker.close(); task.releaseMemoryReservation(); }
      assertReleased(task, baseline);
   }

   @Test void selectionCommitRetriesThenAdvancesAndReleasesReservation() {
      long baseline = MemoryReservation.reservedBytes();
      var task = selectionTask(nonAirReader((level, pos) -> Optional.of(snapshot(BlockPos.ZERO, "source"))), BlockTaskMemoryPolicy.standard());
      assertTrue(task.scanNext(null).isEmpty());
      prepareChangedTransaction(task.transaction());
      task.releaseWriteStagingForCommit();
      assertEquals(0, task.transaction().expectedCount());
      MemoryReservation blocker = fillReservationLimit(commitAdmission(task.transaction()));
      try {
         assertEquals(JournalPreparation.PENDING, task.prepareCommit());
         assertNull(task.operationCommit());
         blocker.close();
         task.prepareCommit();
         task.operationCommit().completion().join();
         assertEquals(JournalPreparation.READY, task.prepareCommit());
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
      } finally { blocker.close(); task.releaseMemoryReservation(); }
      assertReleased(task, baseline);
   }

   @Test void placementCommitHardAdmissionRejectsWithoutStartingCommit() {
      var task = placementTask(rejected());
      prepareChangedTransaction(task.transaction());
      assertEquals(JournalPreparation.FAILED, task.prepareCommit());
      assertNull(task.operationCommit());
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(task));
   }

   @Test void workspaceCommitHardAdmissionRejectsWithoutStartingCommit() {
      var task = workspaceTask(new WorkspaceSnapshotCapture(Map.of()), rejected());
      prepareChangedTransaction(task.transaction());
      assertEquals(TaskStep.MEMORY_UNSAFE, task.prepareCommit());
      assertNull(task.operationCommit());
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(task));
   }

   @Test void selectionCommitHardAdmissionRejectsWithoutStartingCommit() {
      var task = selectionTask(BlockSnapshotReader.live(), rejected());
      prepareChangedTransaction(task.transaction());
      assertEquals(JournalPreparation.FAILED, task.prepareCommit());
      assertNull(task.operationCommit());
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(task));
   }

   private static SubmittedWorkspaceTask workspaceTask(WorkspaceSnapshotCapture capture, BlockTaskMemoryPolicy policy) {
      return new SubmittedWorkspaceTask(UUID.randomUUID(), new OperationWorkspacePlan(List.of()),
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, new WorldJournalPreparation(Runnable::run),
         capture, BlockSnapshotReader.live(), policy);
   }

   private static PlacementTask placementTask(BlockTaskMemoryPolicy policy) {
      return WorldTaskFixtures.placement(Set.of(BlockPos.ZERO), new PlacementTaskPlan(null, null,
         OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD),
         new WorldJournalPreparation(Runnable::run), BlockSnapshotReader.live(), policy);
   }

   private static SelectionOperationTask selectionTask(BlockSnapshotReader snapshots, BlockTaskMemoryPolicy policy) {
      return new SelectionOperationTask(OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         OperationMode.MOVE, OperationConflictMode.REPLACE, false, BlockPos.ZERO.east(), OperationStackRegion.origin(),
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, new WorldJournalPreparation(Runnable::run), snapshots, policy);
   }

   private static BlockSnapshotReader nonAirReader(BlockSnapshotReader capture) {
      return new BlockSnapshotReader() {
         public Optional<ReversibleBlockSnapshot> capture(ServerLevel level, BlockPos position) { return capture.capture(level, position); }
         public boolean isAir(ServerLevel level, BlockPos position) { return false; }
      };
   }

   private static BlockTaskMemoryPolicy rejected() {
      return estimate -> new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, estimate.requestedBytes(), estimate.usableBytes());
   }

   private static MemoryReservation fillReservationLimit(MemoryAdmission target) {
      assertTrue(target.allowed());
      return WorldOperationMemory.reserve(new MemoryAdmission(MemoryAdmissionStatus.ALLOWED,
         target.usableBytes() - MemoryReservation.reservedBytes(), target.usableBytes())).orElseThrow();
   }

   private static ReversibleBlockSnapshot largeSnapshot() {
      CompoundTag tag = new CompoundTag();
      tag.putByteArray("payload", new byte[350_000]);
      return new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, new BlockEntitySnapshot(tag));
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }

   private static void prepareChangedTransaction(WorldChangeTransaction transaction) {
      transaction.recordBefore(snapshot(BlockPos.ZERO, "before"));
      transaction.recordAfter(BlockPos.ZERO, snapshot(BlockPos.ZERO, "after"));
   }

   private static MemoryAdmission commitAdmission(WorldChangeTransaction transaction) {
      return WorldOperationMemory.commitAdmission(transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve());
   }

   private static void assertReleased(io.github.fastformer.server.task.runtime.LeasedJournaledTask task, long baseline) {
      assertFalse(TaskRuntimeFixtures.hasWorkingSet(task));
      assertEquals(0L, task.transaction().snapshotCount());
      assertEquals(0, task.transaction().expectedCount());
      assertNull(task.operationCommit());
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }
}
