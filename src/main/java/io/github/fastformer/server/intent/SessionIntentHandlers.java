package io.github.fastformer.server.intent;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.input.IdleInteractionSnapshots;
import io.github.fastformer.common.mode.BuiltinModes;
import net.minecraft.server.level.ServerPlayer;

/** Owns session action admission, routing, and candidate validation. */
final class SessionIntentHandlers {
   private static final int MAX_ADJUST_STEPS = 128;
   private static final Gate<IntentContext<ServerPlayer>> CONTINUE =
      context -> ServerOperationGates.CONTINUE_INTERACTION.test(context.actor());
   private static final Gate<IntentContext<ServerPlayer>> EDIT = CONTINUE.and(
      context -> ServerOperationGates.AVAILABLE.test(context.actor()));
   private static final Gate<IntentContext<ServerPlayer>> CONFIRM = EDIT.or(
      Gate.<IntentContext<ServerPlayer>>require("smart_fill",
         context -> SessionSystem.of(context.actor()).modes().active(context.actor(), BuiltinModes.SMART_FILL))
         .and(context -> ServerOperationGates.AVAILABLE.test(context.actor())));

   private SessionIntentHandlers() {}

   static void register(IntentBus<ServerPlayer> bus) {
      bus.register(SessionIntents.CycleMode.class, EDIT, SessionIntentHandlers::cycle);
      bus.register(SessionIntents.Adjust.class, EDIT, SessionIntentHandlers::adjust);
      bus.register(SessionIntents.Confirm.class, CONFIRM, SessionIntentHandlers::confirm);
      bus.register(SessionIntents.Submode.class, CONTINUE, SessionIntentHandlers::submode);
   }

   private static IntentResult cycle(IntentContext<ServerPlayer> context, SessionIntents.CycleMode intent) {
      ServerPlayer player = context.actor();
      return SessionSystem.of(player).modes().cycle(player, intent.lineCandidate());
   }

   private static IntentResult adjust(IntentContext<ServerPlayer> context, SessionIntents.Adjust intent) {
      ServerPlayer player = context.actor();
      if (intent.steps() == 0) return IntentResult.pass();
      if (context.source() == IntentSource.COMMAND) {
         if (!SessionSystem.of(player).modes().allowsCommandAdjust(player)) return IntentResult.pass();
      } else if (IdleInteractionSnapshots.idle(player)) return IntentResult.pass();
      int steps = Math.clamp(intent.steps(), -MAX_ADJUST_STEPS, MAX_ADJUST_STEPS);
      SessionSystem.of(player).modes().adjust(player, steps);
      return IntentResult.success();
   }

   private static IntentResult confirm(IntentContext<ServerPlayer> context, SessionIntents.Confirm intent) {
      ServerPlayer player = context.actor();
      if (context.source() == IntentSource.COMMAND && !hasSession(player)
         || context.source() != IntentSource.COMMAND && IdleInteractionSnapshots.idle(player)) return IntentResult.pass();
      return SessionSystem.of(player).modes().confirm(player);
   }

   private static IntentResult submode(IntentContext<ServerPlayer> context, SessionIntents.Submode intent) {
      ServerPlayer player = context.actor();
      boolean available = ServerOperationGates.AVAILABLE.test(player).allowed();
      if (context.source() == IntentSource.COMMAND && (!available || !hasSession(player))) return IntentResult.pass();
      boolean allowed = available && SessionSystem.of(player).modes().allowsSubmode(player);
      SessionSystem.of(player).setModifierHeld(player, allowed && intent.held());
      return IntentResult.handled(allowed);
   }

   private static boolean hasSession(ServerPlayer player) {
      return SessionSystem.of(player).modes().hasEditing(player)
         || SessionSystem.of(player).modes().active(player, BuiltinModes.SMART_FILL);
   }
}
