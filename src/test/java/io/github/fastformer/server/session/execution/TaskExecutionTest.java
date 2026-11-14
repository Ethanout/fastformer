package io.github.fastformer.server.session.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.NoDiskHistoryRepository;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class TaskExecutionTest {
   @Test void prerequisiteBlocksBothTickAndResume() {
      var busy = new AtomicBoolean(true);
      var execution = new CountingExecution(() -> false, ignored -> busy.get());
      var history = history();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = task();
      execution.admit(context.owner(), task);

      execution.tickWorld(null, history);
      execution.resume(context, task, history);
      assertEquals(0, execution.services);

      busy.set(false);
      execution.tickWorld(null, history);
      execution.resume(context, task, history);
      assertEquals(2, execution.services);
      execution.onDiscard();
      history.clear();
   }

   @Test void closingParentPreventsAdmissionTickResumeAndHandover() {
      var closed = new AtomicBoolean();
      var execution = new CountingExecution(closed::get, ignored -> false);
      var history = history();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = task();
      execution.admit(context.owner(), task);
      closed.set(true);

      assertThrows(IllegalStateException.class, () -> execution.admit(UUID.randomUUID(), task()));
      execution.resume(context, task, history);
      execution.tickWorld(null, history);
      execution.handOverBlockedTasks(null, history);
      assertEquals(0, execution.services);
      assertTrue(execution.isCurrent(context.owner(), task), "shutdown must retain ownership until discard");
      assertFalse(execution.queue.handingOver(context.owner()));

      execution.onDiscard();
      assertFalse(execution.active(context.owner()));
      history.clear();
   }

   @Test void staleResumeCannotAdvanceSuccessorAndHandoverCannotResume() {
      var execution = new CountingExecution(() -> false, ignored -> false);
      var history = history();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var old = task();
      var current = task();
      execution.admit(context.owner(), old);
      execution.queue.remove(context.owner(), old);
      execution.admit(context.owner(), current);

      execution.resume(context, old, history);
      assertEquals(0, execution.services);
      execution.resume(context, current, history);
      assertEquals(1, execution.services);

      assertEquals(TaskCancellationResult.RECOVERY_BLOCKED, execution.queue.transfer(context, current,
         (ignored, task, snapshot) -> TaskCancellationResult.RECOVERY_BLOCKED));
      execution.resume(context, current, history);
      assertEquals(1, execution.services);
      assertTrue(execution.isCurrent(context.owner(), current));
      execution.onDiscard();
      history.clear();
   }

   @Test void capturedRecoveryGateBlocksAndReopensResume() {
      var execution = new CountingExecution(() -> false, ignored -> false);
      var history = history();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = task();
      execution.admit(context.owner(), task);
      long recovery = context.writeGate().beginRecovery();

      execution.resume(context, task, history);
      assertEquals(0, execution.services);
      context.writeGate().finishRecovery(recovery, true);
      execution.resume(context, task, history);
      assertEquals(1, execution.services);
      execution.onDiscard();
      history.clear();
   }

   private static PlacementTask task() {
      return PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD));
   }

   private static HistoryService history() {
      return new HistoryService(Gate.require("test", ignored -> true),
         Gate.require("test", ignored -> true), new NoDiskHistoryRepository());
   }

   private static final class CountingExecution extends TaskExecution<PlacementTask> {
      private int services;

      private CountingExecution(BooleanSupplier closed, Predicate<UUID> busy) { super(closed, busy); }

      @Override public void service(WorldTaskContext context, HistoryService history) { services++; }
   }
}
