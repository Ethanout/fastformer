package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationStackRegion;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.MemoryReservationAttempt;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

class ManagerClearServerResourceTest {
   @AfterEach
   void clearManagers() {
      FastPlaceManager.clearServer();
   }

   @Test
   void fastPlaceClearServerDetachesPlacementButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      PlacementTask task = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ));
      assertTrue(task.prepare());
      assertTrue(task.ensureMemoryReservation());
      assertManagerClearLifecycle(task, baseline, true);
   }

   @Test
   void operationClearServerDetachesWorkspaceButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      ClientWorkspacePlacementTask task = new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of()),
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
      setField(task, "desired", Collections.singletonMap(BlockPos.ZERO, null));
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      assertManagerClearLifecycle(task, baseline, false);
   }

   @Test
   void operationClearServerDetachesSelectionButWaitsForIoBeforeReleasingMemory() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      SelectionOperationTask task = new SelectionOperationTask(
         new OperationSelectionVolume(
            OperationSelectionMode.CUBOID, new AABB(0, 0, 0, 1, 1, 1),
            null, List.of(), 0, BlockPos.ZERO, BlockPos.ZERO
         ),
         OperationMode.MOVE, OperationConflictMode.REPLACE, false, BlockPos.ZERO.above(),
         OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
      source(task).add(snapshot(BlockPos.ZERO, "source"));
      assertEquals(MemoryReservationAttempt.ACQUIRED, task.reserveWorkingSet());
      assertManagerClearLifecycle(task, baseline, false);
   }

   private static void assertManagerClearLifecycle(Object task, long baseline, boolean placement) throws Exception {
      UUID owner = UUID.randomUUID();
      CompletableFuture<Boolean> journalIo = new CompletableFuture<>();
      setField(journalPreparation(task), "appendFuture", journalIo);

      CountDownLatch historyStarted = new CountDownLatch(1);
      CompletableFuture<Void> finishHistory = new CompletableFuture<>();
      BlockPos position = BlockPos.ZERO;
      WorldOperationCommit commit = WorldOperationCommit.begin(
         Level.OVERWORLD,
         new BlockingCompressionDeque<>(List.of(snapshot(position, "before")), historyStarted, finishHistory),
         Map.of(position, snapshot(position, "after")),
         null
      );
      setField(task, "operationCommit", commit);
      try {
         assertTrue(historyStarted.await(5, TimeUnit.SECONDS));
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         if (placement) {
            FastPlaceManager.addTaskForTest(owner, (PlacementTask)task);
            FastPlaceManager.clearServer();
            assertFalse(FastPlaceManager.taskActive(owner));
         } else {
            OperationManager.addTaskForTest(owner, (io.github.fastformer.fastplace.task.WorldOperationTask)task);
            OperationManager.clearServer();
            assertFalse(OperationManager.taskActive(owner));
         }
         assertTrue(MemoryReservation.reservedBytes() > baseline);

         journalIo.complete(false);
         assertTrue(MemoryReservation.reservedBytes() > baseline);
         finishHistory.complete(null);
         commit.completion().join();
         awaitReservedBytes(baseline);
      } finally {
         journalIo.complete(false);
         finishHistory.complete(null);
         commit.completion().get(5, TimeUnit.SECONDS);
      }
   }

   private static WorldJournalPreparation journalPreparation(Object task) throws Exception {
      return (WorldJournalPreparation)field(task, "journalPreparation");
   }

   @SuppressWarnings("unchecked")
   private static List<ReversibleBlockSnapshot> source(SelectionOperationTask task) throws Exception {
      return (List<ReversibleBlockSnapshot>)field(task, "source");
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }

   private static Object field(Object target, String name) throws Exception {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
   }

   private static void setField(Object target, String name, Object value) throws Exception {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
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
