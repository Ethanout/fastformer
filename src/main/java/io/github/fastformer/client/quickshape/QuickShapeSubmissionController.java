package io.github.fastformer.client.quickshape;

import io.github.fastformer.client.input.ClientInteractionFeedback;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.placement.effect.PlacementEffectContext;
import io.github.fastformer.common.placement.effect.PlacementEffectRegistry;
import io.github.fastformer.common.placement.plan.PlacementGeometryPlan;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;

/** Connects one player's submission intent to authoritative snapshots and transport. */
public final class QuickShapeSubmissionController {
   private QuickShapeSubmissionController() { }

   /** Handles an admitted confirmation with the data captured when its key was pressed. */
   public static boolean submit(Minecraft minecraft, QuickShapeSubmissionIntent intent,
      QuickShapeSubmissionSnapshot snapshot) {
      if (snapshot == null || !ClientPlacementRouter.canConfirm(minecraft)) return false;
      return begin(minecraft, intent, snapshot);
   }

   public static boolean begin(Minecraft minecraft, QuickShapeSubmissionIntent intent, QuickShapeSubmissionSnapshot snapshot) {
      if (intent.active() || snapshot == null) return false;
      long requestId = ClientPlacementRouter.beginQuickShapeRequest(minecraft);
      if (requestId <= 0 || !intent.begin(requestId, snapshot)) return false;
      ClientInteractionFeedback.show(minecraft, "fastformer.message.quick_shape_submit_waiting");
      return true;
   }

   public static void tick(Minecraft minecraft, QuickShapeSubmissionIntent intent) {
      if (!intent.active()) return;
      var snapshot = intent.snapshot();
      var current = ClientSessionSystem.instance().previews().buildingSubmission();
      if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
         || !snapshot.scope().playerId().equals(minecraft.player.getUUID())
         || !snapshot.scope().dimension().equals(minecraft.level.dimension().location())
         || current.isEmpty() || !current.orElseThrow().equals(snapshot)) {
         fail(minecraft, intent, "fastformer.message.placement_confirm_failed");
         return;
      }
      if (intent.waitingForParameters()) {
         var parameters = ClientSessionSystem.instance().previews().submissionParameters(snapshot);
         if (parameters.isEmpty()) return;
         if (parameters.orElseThrow().prototype().isAir()) {
            fail(minecraft, intent, "fastformer.message.placement_confirm_failed");
            return;
         }
         long requestId = intent.requestId();
         try {
            intent.calculate(plan(minecraft, snapshot, parameters.orElseThrow()), ForkJoinPool.commonPool());
         } catch (RuntimeException exception) {
            intent.cancel();
            FastPlaceClientInput.abortPlacementRequest(requestId);
            ClientInteractionFeedback.show(minecraft, "fastformer.message.placement_generation_failed");
            return;
         }
      }
      // Keep completed work pending while a menu or an unfocused window owns input.
      if (minecraft.screen != null || !minecraft.isWindowActive()) return;
      intent.takeCompleted().ifPresent(completion -> {
         if (completion.outcome() == QuickShapeSubmissionIntent.Outcome.READY
            && ClientPlacementRouter.sendPreparedQuickShape(minecraft, completion.snapshot(), completion.requestId())) return;
         FastPlaceClientInput.abortPlacementRequest(completion.requestId());
         String message = switch (completion.outcome()) {
            case LIMIT_EXCEEDED -> "fastformer.message.quick_shape_submit_limit";
            case CONSTRAINTS_FAILED -> "fastformer.message.face_generation_constraints_failed";
            case MEMORY_UNAVAILABLE -> "fastformer.message.operation_memory_unsafe";
            default -> "fastformer.message.placement_generation_failed";
         };
         ClientInteractionFeedback.show(minecraft, message);
      });
   }

   private static void fail(Minecraft minecraft, QuickShapeSubmissionIntent intent, String message) {
      long requestId = intent.requestId();
      intent.cancel();
      FastPlaceClientInput.abortPlacementRequest(requestId);
      ClientInteractionFeedback.show(minecraft, message);
   }

   private static PlacementGeometryPlan plan(Minecraft minecraft, QuickShapeSubmissionSnapshot snapshot,
      QuickShapeSubmissionParametersPayload parameters) {
      var data = snapshot.data();
      var modes = new FastPlaceGeometry.Modes(data.pointMode(), RaycastPlacement.SURFACE, data.lineMode(),
         data.faceMode(), data.volumeMode(), data.fillMode(), data.angleDegrees(), false,
         parameters.faceTieBias());
      var placement = data.placementContext();
      var context = new PlacementEffectContext(minecraft.player, minecraft.player.getMainHandItem(), parameters.prototype(),
         placement == null ? Direction.Axis.Y : placement.clickedFace().getAxis(), snapshot.points(), modes,
         data.polygonHeightConfirmed(), data.polygonVolumeShape(), placement);
      var effect = data.activePlacementEffect() == null ? null
         : PlacementEffectRegistry.resolve(data.activePlacementEffect(), context).orElseThrow();
      return new PlacementGeometryPlan(snapshot.points(), modes, data.polygonHeightConfirmed(), data.polygonVolumeShape(),
         parameters.maxPlacement(), effect, data.facePolicies());
   }
}
