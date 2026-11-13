package io.github.fastformer.server.intent;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.input.IdleInteractionSnapshots;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Owns session action admission, routing, and candidate validation. */
final class SessionIntentHandlers {
   private static final int MAX_ADJUST_STEPS = 128;
   private static final int MAX_LINE_OFFSET = 128;
   private static final Gate<IntentContext<ServerPlayer>> CONTINUE =
      context -> ServerOperationGates.CONTINUE_INTERACTION.test(context.actor());
   private static final Gate<IntentContext<ServerPlayer>> EDIT = CONTINUE.and(
      context -> ServerOperationGates.AVAILABLE.test(context.actor()));

   private SessionIntentHandlers() {}

   static void register(IntentBus<ServerPlayer> bus) {
      bus.register(SessionIntents.CycleMode.class, EDIT, SessionIntentHandlers::cycle);
      bus.register(SessionIntents.Adjust.class, EDIT, SessionIntentHandlers::adjust);
      bus.register(SessionIntents.Confirm.class, EDIT, SessionIntentHandlers::confirm);
      bus.register(SessionIntents.Submode.class, CONTINUE, SessionIntentHandlers::submode);
   }

   private static IntentResult cycle(IntentContext<ServerPlayer> context, SessionIntents.CycleMode intent) {
      ServerPlayer player = context.actor();
      if (OperationSystem.of(player).active(player)) OperationSystem.of(player).cycleMode(player);
      else if (GeometrySystem.of(player).active(player)) return IntentResult.handled(GeometrySystem.of(player).cycleMode(player));
      else if (QuickShapeSystem.of(player).active(player)) QuickShapeSystem.of(player).cycleStageMode(player, trustedLineCandidate(player, intent.lineCandidate()));
      else if (PlaceableItems.isPlaceable(player.getMainHandItem())) QuickShapeSystem.of(player).cycleStageMode(player);
      else OperationSystem.of(player).cycleMode(player);
      return IntentResult.success();
   }

   private static IntentResult adjust(IntentContext<ServerPlayer> context, SessionIntents.Adjust intent) {
      ServerPlayer player = context.actor();
      if (intent.steps() == 0) return IntentResult.pass();
      if (context.source() == IntentSource.COMMAND) {
         if (!hasSession(player)
            || GeometrySystem.of(player).active(player) && !GeometrySystem.of(player).allows(player, GeometryAction.SCALAR_ADJUST)
            || OperationSystem.of(player).active(player) && !OperationSystem.of(player).selectionReady(player)) return IntentResult.pass();
      } else if (IdleInteractionSnapshots.idle(player)) return IntentResult.pass();
      int steps = Math.clamp(intent.steps(), -MAX_ADJUST_STEPS, MAX_ADJUST_STEPS);
      if (OperationSystem.of(player).active(player)) OperationSystem.of(player).scroll(player, steps);
      else if (GeometrySystem.of(player).active(player)) GeometrySystem.of(player).scroll(player, steps);
      else QuickShapeSystem.of(player).scrollContext(player, steps);
      return IntentResult.success();
   }

   private static IntentResult confirm(IntentContext<ServerPlayer> context, SessionIntents.Confirm intent) {
      ServerPlayer player = context.actor();
      if (context.source() == IntentSource.COMMAND && !hasSession(player)
         || context.source() != IntentSource.COMMAND && IdleInteractionSnapshots.idle(player)) return IntentResult.pass();
      if (GeometrySystem.of(player).active(player)) return IntentResult.handled(GeometrySystem.of(player).fill(player));
      if (OperationSystem.of(player).active(player)) return IntentResult.handled(OperationSystem.of(player).applyConfirmed(player, false));
      if (!QuickShapeSystem.of(player).active(player)) return IntentResult.pass();
      QuickShapeSystem.of(player).fill(player);
      return IntentResult.success();
   }

   private static IntentResult submode(IntentContext<ServerPlayer> context, SessionIntents.Submode intent) {
      ServerPlayer player = context.actor();
      boolean available = ServerOperationGates.AVAILABLE.test(player).allowed();
      if (context.source() == IntentSource.COMMAND && (!available || !hasSession(player))) return IntentResult.pass();
      boolean allowed = available && (!GeometrySystem.of(player).active(player) || GeometrySystem.of(player).allows(player, GeometryAction.SUBMODE));
      SessionSystem.of(player).setModifierHeld(player, allowed && intent.held());
      return IntentResult.handled(allowed);
   }

   private static boolean hasSession(ServerPlayer player) {
      return GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player) || QuickShapeSystem.of(player).active(player);
   }

   private static BlockPos trustedLineCandidate(ServerPlayer player, BlockPos candidate) {
      if (candidate == null) return null;
      QuickShapeDraft session = QuickShapeSystem.of(player).session(player).orElse(null);
      if (session == null || QuickShapeStage.resolve(session.points().size(),
         FastPlaceSettings.load(player).faceMode(), session.polygonClosed()) != QuickShapeStage.LINE) return null;
      BlockPos first = session.points().getFirst();
      int dx = candidate.getX() - first.getX();
      int dy = candidate.getY() - first.getY();
      int dz = candidate.getZ() - first.getZ();
      if (Math.abs(dx) > MAX_LINE_OFFSET || Math.abs(dy) > MAX_LINE_OFFSET || Math.abs(dz) > MAX_LINE_OFFSET) return null;
      double reach = LongRangeBlockRaycast.MAX_REACH;
      if ((double) dx * dx + (double) dy * dy + (double) dz * dz > reach * reach) return null;
      return candidate.immutable();
   }
}
