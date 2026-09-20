package io.github.fastformer.client.quickshape;

import io.github.fastformer.client.input.ClientInteractionFeedback;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectContext;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry;
import io.github.fastformer.fastplace.placement.plan.PlacementGeometryPlan;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;

/** Connects one player's submission intent to authoritative snapshots and transport. */
public final class QuickShapeSubmissionController {
   private QuickShapeSubmissionController() { }

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
      var current = FastPlaceClientPreview.buildingSubmission();
      if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
         || !snapshot.scope().playerId().equals(minecraft.player.getUUID())
         || !snapshot.scope().dimension().equals(minecraft.level.dimension().location())
         || current.isEmpty() || !current.orElseThrow().equals(snapshot)) {
         fail(minecraft, intent, "fastformer.message.placement_confirm_failed");
         return;
      }
      if (intent.waitingForParameters()) {
         var parameters = FastPlaceClientPreview.submissionParameters(snapshot);
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
         parameters.faceTieBias(), data.faceRasterizationMode());
      var placement = data.placementContext();
      var context = new PlacementEffectContext(minecraft.player, minecraft.player.getMainHandItem(), parameters.prototype(),
         placement == null ? Direction.Axis.Y : placement.clickedFace().getAxis(), snapshot.points(), modes,
         data.polygonHeightConfirmed(), data.polygonVolumeShape(), placement);
      var effect = data.activePlacementEffect() == null ? null
         : PlacementEffectRegistry.resolve(data.activePlacementEffect(), context).orElseThrow();
      return new PlacementGeometryPlan(snapshot.points(), modes, data.polygonHeightConfirmed(), data.polygonVolumeShape(),
         parameters.maxPlacement(), effect);
   }
}
