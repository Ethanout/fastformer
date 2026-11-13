package io.github.fastformer.server.intent;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.QuickShapeSystem;
import net.minecraft.server.level.ServerPlayer;

/** Owns geometry action admission; workflows retain validation, state and feedback. */
final class GeometryIntentHandlers {
   private static final Gate<IntentContext<ServerPlayer>> AVAILABLE =
      context -> ServerOperationGates.AVAILABLE.test(context.actor());
   private static final Gate<IntentContext<ServerPlayer>> SELECT_MODE = AVAILABLE.and(
      context -> ServerOperationGates.CONTINUE_INTERACTION.test(context.actor()));

   private GeometryIntentHandlers() {}

   static void register(IntentBus<ServerPlayer> bus) {
      bus.register(GeometryIntents.SelectMode.class, SELECT_MODE, GeometryIntentHandlers::selectMode);
      bus.register(GeometryIntents.ConePlane.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).setConePlaneMode(context.actor(), intent.mode())));
      bus.register(GeometryIntents.PolyhedronSize.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).setPolyhedronSizeMode(context.actor(), intent.mode())));
      bus.register(GeometryIntents.ConeEllipse.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).setConeEllipse(context.actor(), intent.x(), intent.z())));
      bus.register(GeometryIntents.Euler.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).rotateEuler(context.actor(), intent.x(), intent.y(), intent.z())));
      bus.register(GeometryIntents.Quaternion.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).rotateQuaternion(context.actor(), intent.w(), intent.x(), intent.y(), intent.z())));
      bus.register(GeometryIntents.Matrix.class, AVAILABLE, (context, intent) ->
         IntentResult.handled(GeometrySystem.of(context.actor()).rotateMatrix(context.actor(), intent.values())));
      bus.register(GeometryIntents.RotationStep.class, AVAILABLE, (context, intent) -> {
         if (intent.axis() < 0 || intent.axis() > 2) return IntentResult.pass();
         return IntentResult.handled(GeometrySystem.of(context.actor()).rotateSnap(
            context.actor(), intent.axis(), intent.steps(), intent.fine()));
      });
   }

   private static IntentResult selectMode(IntentContext<ServerPlayer> context, GeometryIntents.SelectMode intent) {
      ServerPlayer player = context.actor();
      if (QuickShapeSystem.of(player).active(player) || OperationSystem.of(player).active(player)) return IntentResult.pass();
      GeometrySystem geometry = GeometrySystem.of(player);
      if (geometry.active(player) && !geometry.awaitingFirstPoint(player)) return IntentResult.pass();
      geometry.selectMode(player, intent.mode());
      return IntentResult.success();
   }
}
