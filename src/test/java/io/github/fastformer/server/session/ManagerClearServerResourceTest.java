package io.github.fastformer.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.server.task.block.SelectionOperationTask;
import io.github.fastformer.server.task.block.WorldTaskFixtures;
import io.github.fastformer.server.task.runtime.LeasedJournaledTask;
import io.github.fastformer.server.task.runtime.TaskRuntimeFixtures;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerClearServerResourceTest {
   @TempDir Path directory;
   private final ArrayDeque<Runnable> journalIo = new ArrayDeque<>();
   private final WorldJournalPreparation preparation = new WorldJournalPreparation(journalIo::addLast);
   private final SessionSystem runtime = new SessionSystem();
   private final QuickShapeSystem quickShape = runtime.quickShape;
   private final OperationSystem operation = runtime.operation;
   @AfterEach
   void clearManagers() {
      runtime.close();
   }

   @Test
   void fastPlaceClearServerDetachesPlacementButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      PlacementTask task = WorldTaskFixtures.placement(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ), preparation);
      assertTrue(task.prepare());
      assertTrue(task.ensureMemoryReservation());
      assertManagerClearLifecycle(task, baseline, true);
   }

   @Test
   void operationClearServerDetachesWorkspaceButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      SubmittedWorkspaceTask task = WorldTaskFixtures.workspace(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of()),
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, preparation
      );
      TaskRuntimeFixtures.reserveWorkingSet(task, 64);
      assertManagerClearLifecycle(task, baseline, false);
   }

   @Test
   void operationClearServerDetachesSelectionButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      SelectionOperationTask task = WorldTaskFixtures.selection(
         new OperationSelectionVolume(
            OperationSelectionMode.CUBOID, new AABB(0, 0, 0, 1, 1, 1),
            null, List.of(), 0, BlockPos.ZERO, BlockPos.ZERO
         ),
         OperationMode.MOVE, OperationConflictMode.REPLACE, false, BlockPos.ZERO.above(),
         OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, preparation
      );
      TaskRuntimeFixtures.reserveWorkingSet(task, 64);
      assertManagerClearLifecycle(task, baseline, false);
   }

   private void assertManagerClearLifecycle(LeasedJournaledTask task, long baseline, boolean placement) throws Exception {
      UUID owner = UUID.randomUUID();
      var journal = RecoveryJournalTestAccess.open(directory);
      preparation.poll(() -> Optional.of(journal));
      journalIo.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));
      assertEquals(JournalPreparation.PENDING, preparation.pollAppend(() -> false));

      CountDownLatch historyStarted = new CountDownLatch(1);
      CompletableFuture<Void> finishHistory = new CompletableFuture<>();
      BlockPos position = BlockPos.ZERO;
      WorldOperationCommit commit = WorldOperationCommit.begin(
         Level.OVERWORLD,
         new BlockingCompressionDeque<>(List.of(snapshot(position, "before")), historyStarted, finishHistory),
         Map.of(position, snapshot(position, "after")),
         null, taskBody -> CompletableFuture.runAsync(taskBody)
      );
      TaskRuntimeFixtures.installCommit(task, commit);
      try {
         assertTrue(historyStarted.await(5, TimeUnit.SECONDS));
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         if (placement) {
            runtime.placementTasks().admit(owner, (PlacementTask)task);
            runtime.close();
            assertFalse(runtime.placementTasks().active(owner));
         } else {
            runtime.operationTasks().admit(owner, (io.github.fastformer.server.task.WorldTask)task);
            runtime.close();
            assertFalse(runtime.operationTasks().active(owner));
         }
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         journalIo.removeFirst().run();
         assertTrue(MemoryReservation.reservedBytes() > baseline);
         finishHistory.complete(null);
         commit.completion().join();
         awaitReservedBytes(baseline);
      } finally {
         if (!journalIo.isEmpty()) journalIo.removeFirst().run();
         finishHistory.complete(null);
         commit.completion().get(5, TimeUnit.SECONDS);
         journal.discardUnused();
      }
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }

   private static void awaitReservedBytes(long expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (MemoryReservation.reservedBytes() != expected && System.nanoTime() < deadline) {
         Thread.sleep(1);
      }
      assertEquals(expected, MemoryReservation.reservedBytes());
   }

   private static final class BlockingCompressionDeque<E> extends ArrayDeque<E> {
      private final CountDownLatch started;
      private final CompletableFuture<Void> finish;

      private BlockingCompressionDeque(
         Collection<? extends E> values, CountDownLatch started, CompletableFuture<Void> finish
      ) {
         super(values);
         this.started = started;
         this.finish = finish;
      }

      @Override
      public Iterator<E> descendingIterator() {
         started.countDown();
         finish.join();
         return super.descendingIterator();
      }
   }
}
