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
   }
}
