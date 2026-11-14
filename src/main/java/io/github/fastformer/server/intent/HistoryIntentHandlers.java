package io.github.fastformer.server.intent;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.fill.SmartFillSystem;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.server.level.ServerPlayer;

/** Routes history operations without blocking task cancellation behind ordinary editing admission. */
final class HistoryIntentHandlers {
   enum UndoRoute { BLOCKED, SESSION, FAST_PLACE_TASK, OPERATION_TASK, HISTORY }
   private static final Gate<IntentContext<ServerPlayer>> AVAILABLE =
      context -> ServerOperationGates.AVAILABLE.test(context.actor());

   private HistoryIntentHandlers() {}

   static void register(IntentBus<ServerPlayer> bus) {
      bus.register(HistoryIntents.Undo.class, AVAILABLE, HistoryIntentHandlers::undo);
      bus.register(HistoryIntents.Redo.class, AVAILABLE, HistoryIntentHandlers::redo);
      bus.register(HistoryIntents.Back.class, AVAILABLE.and(Gate.require("history",
         context -> !HistorySystem.of(context.actor()).busy(context.actor()))),
         (context, intent) -> IntentResult.handled(SessionRollback.undo(context.actor())));
      bus.register(HistoryIntents.Quit.class, Gate.require("cancellation", context -> true), HistoryIntentHandlers::quit);
   }

   private static IntentResult undo(IntentContext<ServerPlayer> context, HistoryIntents.Undo intent) {
      ServerPlayer player = context.actor();
      var history = HistorySystem.of(player);
      if (history.snapshotRetryAvailable(player)) return IntentResult.handled(history.requestUndo(player, intent.count()));
      UndoRoute route = undoRoute(history.busy(player),
         intent.scope() == HistoryIntents.UndoScope.SESSION_OR_WORLD && SessionRollback.active(player),
         SessionSystem.of(player).placementTasks().active(player), SessionSystem.of(player).operationTasks().active(player));
      return IntentResult.handled(switch (route) {
         case BLOCKED -> false;
         case SESSION -> SessionRollback.undo(player);
         case FAST_PLACE_TASK -> finishTaskCancellation(player, SessionSystem.of(player).placementTasks().cancel(player), intent.count());
         case OPERATION_TASK -> finishTaskCancellation(player, SessionSystem.of(player).operationTasks().cancel(player), intent.count());
         case HISTORY -> history.requestUndo(player, intent.count());
      });
   }

   private static IntentResult redo(IntentContext<ServerPlayer> context, HistoryIntents.Redo intent) {
      ServerPlayer player = context.actor();
      var history = HistorySystem.of(player);
      if (context.source() == IntentSource.COMMAND || history.snapshotRetryAvailable(player)) {
         return IntentResult.handled(history.requestRedo(player, intent.count()));
      }
      if (history.busy(player)) return IntentResult.pass();
      if (OperationSystem.of(player).active(player)) {
         OperationSystem.of(player).redo(player);
         return IntentResult.success();
      }
      return IntentResult.handled(!SessionRollback.active(player) && history.requestRedo(player, intent.count()));
   }

   private static IntentResult quit(IntentContext<ServerPlayer> context, HistoryIntents.Quit intent) {
      ServerPlayer player = context.actor();
      if (SmartFillSystem.active(player)) {
         SmartFillSystem.cancel(player);
         return IntentResult.success();
      }
      if (SessionRollback.active(player) || SessionSystem.of(player).placementTasks().active(player) || SessionSystem.of(player).operationTasks().active(player)
         || HistorySystem.of(player).restoreActive(player)) {
         SessionSystem.of(player).quit(player);
         return IntentResult.success();
      }
      return IntentResult.pass();
   }

   private static boolean finishTaskCancellation(ServerPlayer player, TaskCancellationResult result, int count) {
      if (!result.handled()) return false;
      if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.task_cancelled_before_write"));
      } else if (result == TaskCancellationResult.RECOVERY_BLOCKED) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.task_recovery_blocked"));
      }
      int remaining = HistoryService.remainingAfterUncommittedUndo(count);
      if (remaining > 0 && (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE || result == TaskCancellationResult.ROLLBACK_STARTED)) {
         HistorySystem.of(player).deferUndoAfterRecovery(player, remaining);
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.task_cancelled_batch_deferred"));
      }
      return true;
   }

   static UndoRoute undoRoute(boolean historyBusy, boolean sessionActive, boolean placementTask, boolean selectionTask) {
      if (historyBusy) return UndoRoute.BLOCKED;
      if (sessionActive) return UndoRoute.SESSION;
      if (placementTask) return UndoRoute.FAST_PLACE_TASK;
      if (selectionTask) return UndoRoute.OPERATION_TASK;
      return UndoRoute.HISTORY;
   }
}
