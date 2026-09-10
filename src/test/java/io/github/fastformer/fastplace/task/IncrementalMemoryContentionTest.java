package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
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
