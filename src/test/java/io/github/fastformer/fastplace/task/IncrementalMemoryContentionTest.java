package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.MemoryAdmission;
import io.github.fastformer.fastplace.world.MemoryAdmissionStatus;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.JournalPreparation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class IncrementalMemoryContentionTest {
   private static final long CAPTURED_NBT_BYTES = 1_048_576L;

   @Test
   void workspaceRetriesCapturedSnapshotGrowthWithoutCountingItTwice() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      ClientWorkspacePlacementTask task = workspaceTask();
      BlockPos position = BlockPos.ZERO;
      setField(task, "desired", Collections.singletonMap(position, null));
      setField(task, "captureIterator", Collections.emptyIterator());
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());

      WorldChangeTransaction transaction = task.transaction();
      transaction.recordExpected(position, snapshot(position));
      setField(task, "blockEntityReserve", CAPTURED_NBT_BYTES);
      MemoryReservation blocker = fillReservationLimit(
         WorldOperationMemory.snapshotAdmission(1L, CAPTURED_NBT_BYTES)
      );
      try {
         assertEquals("RETRY", invoke(task, "captureNext", new Class<?>[]{serverLevelClass()}, new Object[]{null}).toString());
         assertEquals(1, transaction.expectedCount());
         assertEquals(CAPTURED_NBT_BYTES, longField(task, "blockEntityReserve"));

         blocker.close();
         assertEquals("ADVANCED", invoke(task, "captureNext", new Class<?>[]{serverLevelClass()}, new Object[]{null}).toString());
         assertEquals(1, transaction.expectedCount());
         assertEquals(CAPTURED_NBT_BYTES, longField(task, "blockEntityReserve"));
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void selectionRetriesCompletedScanWithoutRepeatingSnapshotState() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      SelectionOperationTask task = selectionTask();
      BlockPos position = BlockPos.ZERO;
      snapshotList(task).add(snapshot(position));
      task.transaction().recordExpected(position, snapshot(position));
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      setField(task, "blockEntityReserve", CAPTURED_NBT_BYTES);
      setEnumField(task, "phase", "SCAN_COMPLETE");
      MemoryReservation blocker = fillReservationLimit(
         WorldOperationMemory.snapshotAdmission(1L, CAPTURED_NBT_BYTES)
      );
      try {
         assertEquals(OperationTaskResult.ACTIVE, operationResult(invoke(task, "finishScan")));
         assertEquals("SCAN_COMPLETE", field(task, "phase").toString());
         assertEquals(1, snapshotList(task).size());
         assertEquals(1, task.transaction().expectedCount());
         assertEquals(CAPTURED_NBT_BYTES, longField(task, "blockEntityReserve"));

         blocker.close();
         assertTrue(((Optional<?>)invoke(task, "finishScan")).isEmpty());
         assertEquals("JOURNAL", field(task, "phase").toString());
         assertEquals(1, snapshotList(task).size());
         assertEquals(1, task.transaction().expectedCount());
         assertEquals(CAPTURED_NBT_BYTES, longField(task, "blockEntityReserve"));
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void hardAdmissionRejectsWorkspaceAndSelectionProgress() throws Exception {
      ClientWorkspacePlacementTask workspace = workspaceTask();
      setField(workspace, "desired", Collections.singletonMap(BlockPos.ZERO, null));
      setField(workspace, "captureIterator", Collections.emptyIterator());
      setField(workspace, "blockEntityReserve", Long.MAX_VALUE);
      assertEquals(
         "REJECTED",
         invoke(workspace, "captureNext", new Class<?>[]{serverLevelClass()}, new Object[]{null}).toString()
      );

      SelectionOperationTask selection = selectionTask();
      snapshotList(selection).add(snapshot(BlockPos.ZERO));
      setField(selection, "blockEntityReserve", Long.MAX_VALUE);
      setEnumField(selection, "phase", "SCAN_COMPLETE");
      assertEquals(OperationTaskResult.MEMORY_UNSAFE, operationResult(invoke(selection, "finishScan")));
   }

   @Test
   void placementCommitRetriesThenAdvancesAndReleasesReservation() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      PlacementTask task = placementTask();
      assertTrue(task.prepare());
      assertTrue(task.ensureMemoryReservation());
      WorldChangeTransaction transaction = (WorldChangeTransaction)field(task, "transaction");
      prepareChangedTransaction(transaction);

      MemoryReservation blocker = fillReservationLimit(commitAdmission(transaction));
      try {
         assertEquals(JournalPreparation.PENDING, task.prepareCommit());
         assertNull(field(task, "operationCommit"));

         blocker.close();
         assertEquals(JournalPreparation.READY, awaitPlacementCommit(task));
         WorldOperationCommit commit = operationCommit(task);
         assertNotNull(commit);
         task.releaseMemoryReservation();
         commit.completion().join();
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertNull(field(task, "memoryReservation"));
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void workspaceCommitRetriesThenAdvancesAndReleasesReservation() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      ClientWorkspacePlacementTask task = workspaceTask();
      setField(task, "desired", Collections.singletonMap(BlockPos.ZERO, null));
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      prepareChangedTransaction(task.transaction());

      MemoryReservation blocker = fillReservationLimit(commitAdmission(task.transaction()));
      try {
         assertEquals(OperationTaskResult.ACTIVE, invoke(task, "prepareCommit"));
         assertNull(task.operationCommit());

         blocker.close();
         assertEquals(OperationTaskResult.COMPLETE, awaitOperationCommit(task, "prepareCommit"));
         assertNotNull(task.operationCommit());
         task.releaseMemoryReservation();
         task.operationCommit().completion().join();
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertNull(field(task, "memoryReservation"));
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void selectionCommitRetriesThenAdvancesAndReleasesReservation() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      SelectionOperationTask task = selectionTask();
      snapshotList(task).add(snapshot(BlockPos.ZERO));
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      prepareChangedTransaction(task.transaction());

      MemoryReservation blocker = fillReservationLimit(commitAdmission(task.transaction()));
      try {
         assertEquals(JournalPreparation.PENDING, invoke(task, "prepareCommit"));
         assertNull(task.operationCommit());

         blocker.close();
         assertEquals(JournalPreparation.READY, awaitJournalCommit(task, "prepareCommit"));
         assertNotNull(task.operationCommit());
         task.releaseMemoryReservation();
         task.operationCommit().completion().join();
      } finally {
         blocker.close();
         task.releaseMemoryReservation();
      }
      assertNull(field(task, "memoryReservation"));
      assertEquals(baseline, MemoryReservation.reservedBytes());
   }

   @Test
   void placementCommitHardAdmissionRejectsWithoutStartingCommit() throws Exception {
      PlacementTask task = placementTask();
      prepareHardRejectedTransaction((WorldChangeTransaction)field(task, "transaction"));

      assertEquals(JournalPreparation.FAILED, task.prepareCommit());
      assertNull(field(task, "operationCommit"));
      assertNull(field(task, "memoryReservation"));
   }

   @Test
   void workspaceCommitHardAdmissionRejectsWithoutStartingCommit() throws Exception {
      ClientWorkspacePlacementTask task = workspaceTask();
      prepareHardRejectedTransaction(task.transaction());

      assertEquals(OperationTaskResult.MEMORY_UNSAFE, invoke(task, "prepareCommit"));
      assertNull(task.operationCommit());
      assertNull(field(task, "memoryReservation"));
   }

   @Test
   void selectionCommitHardAdmissionRejectsWithoutStartingCommit() throws Exception {
      SelectionOperationTask task = selectionTask();
      prepareHardRejectedTransaction(task.transaction());

      assertEquals(JournalPreparation.FAILED, invoke(task, "prepareCommit"));
      assertNull(task.operationCommit());
      assertNull(field(task, "memoryReservation"));
   }

   private static MemoryReservation fillReservationLimit(MemoryAdmission target) {
      assertTrue(target.allowed());
      long remaining = target.usableBytes() - MemoryReservation.reservedBytes();
      return WorldOperationMemory.reserve(new MemoryAdmission(
         MemoryAdmissionStatus.ALLOWED, remaining, target.usableBytes()
      )).orElseThrow();
   }

   private static ClientWorkspacePlacementTask workspaceTask() {
      return new ClientWorkspacePlacementTask(
         java.util.UUID.randomUUID(), new OperationWorkspacePlan(List.of()),
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
   }

   private static PlacementTask placementTask() {
      return PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ));
   }

   private static void prepareChangedTransaction(WorldChangeTransaction transaction) {
      ReversibleBlockSnapshot before = snapshot(BlockPos.ZERO, "before");
      ReversibleBlockSnapshot after = snapshot(BlockPos.ZERO, "after");
      transaction.recordBefore(before);
      transaction.recordAfter(BlockPos.ZERO, after);
   }

   private static void prepareHardRejectedTransaction(WorldChangeTransaction transaction) throws Exception {
      prepareChangedTransaction(transaction);
      setField(transaction, "commitBlockEntityReserve", Long.MAX_VALUE);
   }

   private static MemoryAdmission commitAdmission(WorldChangeTransaction transaction) {
      return WorldOperationMemory.commitAdmission(
         transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
      );
   }

   private static JournalPreparation awaitPlacementCommit(PlacementTask task) {
      task.prepareCommit();
      try {
         operationCommit(task).completion().join();
      } catch (Exception exception) {
         throw new IllegalStateException(exception);
      }
      return task.prepareCommit();
   }

   private static WorldOperationCommit operationCommit(Object task) throws Exception {
      return (WorldOperationCommit)field(task, "operationCommit");
   }

   private static OperationTaskResult awaitOperationCommit(Object task, String method) throws Exception {
      invoke(task, method);
      ((WorldOperationCommit)field(task, "operationCommit")).completion().join();
      return (OperationTaskResult)invoke(task, method);
   }

   private static JournalPreparation awaitJournalCommit(Object task, String method) throws Exception {
      invoke(task, method);
      ((WorldOperationCommit)field(task, "operationCommit")).completion().join();
      return (JournalPreparation)invoke(task, method);
   }

   private static SelectionOperationTask selectionTask() {
      return new SelectionOperationTask(
         new OperationSelectionVolume(
            OperationSelectionMode.CUBOID, new AABB(0, 0, 0, 1, 1, 1),
            null, List.of(), 0, BlockPos.ZERO, BlockPos.ZERO
         ),
         OperationMode.MOVE, OperationConflictMode.REPLACE, false, new BlockPos(1, 0, 0),
         OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position) {
      return new ReversibleBlockSnapshot(
         position, null, null, null
      );
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }

   @SuppressWarnings("unchecked")
   private static List<ReversibleBlockSnapshot> snapshotList(SelectionOperationTask task) throws Exception {
      return (List<ReversibleBlockSnapshot>)field(task, "source");
   }

   private static OperationTaskResult operationResult(Object optional) {
      return (OperationTaskResult)((Optional<?>)optional).orElseThrow();
   }

   private static Class<?> serverLevelClass() throws ClassNotFoundException {
      return net.minecraft.server.level.ServerLevel.class;
   }

   private static Object invoke(Object target, String name) throws Exception {
      return invoke(target, name, new Class<?>[0], new Object[0]);
   }

   private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object[] arguments)
      throws Exception {
      Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method.invoke(target, arguments);
   }

   private static Object field(Object target, String name) throws Exception {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
   }

   private static long longField(Object target, String name) throws Exception {
      return (long)field(target, name);
   }

   private static void setField(Object target, String name, Object value) throws Exception {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   private static void setEnumField(Object target, String name, String constant) throws Exception {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, Enum.valueOf((Class<? extends Enum>)field.getType(), constant));
   }
}
