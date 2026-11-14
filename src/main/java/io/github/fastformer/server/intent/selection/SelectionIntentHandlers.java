package io.github.fastformer.server.intent.selection;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.core.gate.GateResult;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.server.level.ServerPlayer;

/** Selection action admission; pointer identity remains at the input boundary. */
public final class SelectionIntentHandlers {
   private static final Gate<IntentContext<ServerPlayer>> AVAILABLE =
      context -> ServerOperationGates.AVAILABLE.test(context.actor());
   private static final Gate<IntentContext<ServerPlayer>> INPUT_APPLY = AVAILABLE.and(context ->
      context.source() == IntentSource.COMMAND ? GateResult.allow()
         : ServerOperationGates.CONTINUE_INTERACTION.test(context.actor()));
   private static final Gate<IntentContext<ServerPlayer>> EDIT = AVAILABLE
      .and(context -> ServerOperationGates.CONTINUE_INTERACTION.test(context.actor()))
      .and(Gate.require("selection", context -> OperationSystem.of(context.actor()).active(context.actor())));

   private SelectionIntentHandlers() {}

   public static void register(IntentBus<ServerPlayer> bus) {
      bus.register(SelectionIntents.Apply.class, INPUT_APPLY, (context, intent) -> {
         var player = context.actor();
         var selection = OperationSystem.of(player);
         return IntentResult.handled(context.source() == IntentSource.COMMAND
            ? selection.apply(player, intent.copy()) : selection.applyConfirmed(player, intent.copy()));
      });
      bus.register(SelectionIntents.CycleMode.class, AVAILABLE, (context, intent) -> {
         var player = context.actor();
         OperationSystem.of(player).cycleMode(player);
         return IntentResult.handled(OperationSystem.of(player).active(player));
      });
      bus.register(SelectionIntents.SelectMode.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(OperationSystem.of(context.actor()).setSelectionMode(context.actor(), intent.mode())));
      bus.register(SelectionIntents.ConflictMode.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(OperationSystem.of(context.actor()).setConflictMode(context.actor(), intent.mode())));
      bus.register(SelectionIntents.SelectPoint.class, EDIT, (context, intent) -> {
         OperationSystem.of(context.actor()).selectPoint(context.actor(), intent.index());
         return IntentResult.success();
      });
      bus.register(SelectionIntents.RemovePoint.class, EDIT, (context, intent) -> {
         OperationSystem.of(context.actor()).removePoint(context.actor(), intent.index());
         return IntentResult.success();
      });
      bus.register(SelectionIntents.CloseBase.class, EDIT, (context, intent) ->
         IntentResult.handled(OperationSystem.of(context.actor()).closePrismBase(context.actor())));
      bus.register(SelectionIntents.Expand.class, EDIT, (context, intent) -> {
         var player = context.actor();
         var selection = OperationSystem.of(player);
         var session = selection.session(player).orElseThrow();
         if (session.selectionMode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID) {
            selection.expandTo(player, intent.point());
         } else selection.addExtraPoint(player, intent.point());
         return IntentResult.success();
      });
      bus.register(SelectionIntents.InsertPoint.class, EDIT, (context, intent) -> {
         OperationSystem.of(context.actor()).insertPoint(context.actor(), intent.index(), intent.point());
         return IntentResult.success();
      });
      bus.register(SelectionIntents.DragPoint.class, EDIT, (context, intent) -> {
         OperationSystem.of(context.actor()).dragPoint(context.actor(), intent.index(), intent.target(), intent.constraint(), intent.finish());
         return IntentResult.success();
      });
      bus.register(SelectionIntents.Extend.class, EDIT, (context, intent) -> {
         if (intent.axis() < 0 || intent.axis() > 8) return IntentResult.pass();
         OperationSystem.of(context.actor()).extend(context.actor(), intent.axis(), intent.positive(),
            Math.clamp(intent.steps(), -128, 128), intent.finish());
         return IntentResult.success();
      });
   }
}
