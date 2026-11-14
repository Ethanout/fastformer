package io.github.fastformer.client.preview.assembly;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.idle.IdlePreviewInput;
import io.github.fastformer.client.preview.frame.PreviewAnimation;
import io.github.fastformer.client.preview.block.BlockPreviewInput;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.common.world.BlockSnapshot;
import java.util.Map;
import java.util.Optional;
import io.github.fastformer.client.preview.frame.PreviewLayer;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.mode.PreviewObservation;
import io.github.fastformer.client.preview.frame.PreviewFrame;
import io.github.fastformer.client.preview.frame.ModifierReticleMode;
import io.github.fastformer.client.preview.geometry.GeometryPreviewModel;
import io.github.fastformer.client.preview.selection.SelectionPreviewInput;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.feedback.SelectionFaceFeedback;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.client.render.theme.VisualThemes;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.minecraft.world.phys.HitResult;

/** Captures visual time and pointer facts before the HUD and world draw the frame. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientPreviewFrameSystem {
   private ClientPreviewFrameSystem() {}

   @SubscribeEvent
   public static void beginFrame(RenderFrameEvent.Pre event) {
      PreviewAnimation.advance(System.nanoTime(), VisualThemes.value("boil_fps", 8),
         (int)VisualThemes.value("boil_sheets", 3));
      Minecraft minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var operations = sessions.operations();
      updateTheme(sessions);
      sessions.inputSession().pointer().invalidate();
      boolean visible = minecraft.player != null && minecraft.level != null
         && minecraft.screen == null && minecraft.isWindowActive();
      var pointer = operations.pointer().captureFrame(visible ? InteractionContext.capture(minecraft) : null);
      operations.updateVisualHover(operations.active() ? pointer.target().orElse(null) : null);
      ClientPreviewSystem.instance().captureIdleFrame(idleInput(minecraft, sessions));
      var replacement = QuickReplaceMode.preview(minecraft);
      var replacementInput = replacement == null ? BlockPreviewInput.EMPTY
         : new BlockPreviewInput(Map.of(replacement.position(), new BlockSnapshot(replacement.state(), null)));
      ClientPreviewSystem.instance().captureToolFrames(
         new BlockPreviewInput(sessions.inputSession().smartFill().blocks()), replacementInput);
      captureVisualFrame(minecraft, PreviewAnimation.current().nanos());
   }

   private static IdlePreviewInput idleInput(Minecraft minecraft, ClientSessionSystem sessions) {
      var player = minecraft.player;
      boolean modifier = PointerDragSnapshotView.modifierHeld(sessions.inputSession());
      if (!sessions.modeView().idleActionsAllowed() || player == null || minecraft.level == null
         || !player.isCreative() || !sessions.previews().building().enabled()
         || minecraft.screen != null || minecraft.getCameraEntity() != player
         || !modifier && minecraft.hitResult != null
            && minecraft.hitResult.getType() == HitResult.Type.ENTITY) return null;
      boolean placeable = PlaceableItems.isPlaceable(player.getMainHandItem());
      var hit = sessions.inputSession().pointer().selection(player, modifier).hit();
      boolean embedded = modifier && placeable;
      return embedded || LongRangeBlockRaycast.beyondInteractionRange(player, hit)
         ? new IdlePreviewInput(player, hit, embedded, InteractionRaycastRules.passesThroughFluids(player, modifier), placeable)
         : null;
   }

   private static long renderedThemeRevision = -1;
   private static void updateTheme(ClientSessionSystem sessions) {
      long revision = VisualThemes.revision();
      if (renderedThemeRevision == revision) return;
      sessions.inputSession().invalidateGeometryPlan();
      ClientPreviewSystem.instance().invalidateResources();
      SmoothReticlePostEffect.reloadResources();
      renderedThemeRevision = revision;
   }

   public static void captureVisualFrame(Minecraft minecraft, long now) {
      var sessions = ClientSessionSystem.instance();
      var system = ClientPreviewSystem.instance();
      var input = sessions.inputSession();
      var operations = sessions.operations();
      var previews = sessions.previews();
      var remote = operations.remotePointer();
      system.selection().beginFrame();
      boolean visible = minecraft.player != null && minecraft.level != null && minecraft.screen == null;
      var reticle = visible ? FastPlaceClientInput.modifierReticleMode() : ModifierReticleMode.NONE;
      SmoothReticlePostEffect.capture(reticle, now);
      GeometryPreviewModel geometry = visible && previews.geometry().active() ? ClientWorldPreviewCapture.geometryPreviewModel() : null;
      var gizmo = visible ? remote.operationGizmo() : null;
      var target = visible ? operations.visualHoverIntent() : null;
      var pointer = visible ? operations.pointer().frame().target().orElse(null) : null;
      var workspaceFace = target instanceof OperationInteractionIntent.Face face ? face : null;
      var dragged = PointerDragSnapshotView.operationFaceHit(input);
      var fallback = visible ? remote.operationFaceHit() : null;
      boolean serverSelection = previews.operation().active() && !operations.selectionEdits().smartTool()
         && !operations.active() && !operations.selectionEdits().selectionDraftActive();
      var initial = new SelectionPreviewInput(previews.operation(), pointer, gizmo, dragged, fallback,
         null, null, serverSelection, operations.active());
      var hit = visible && !operations.active() ? initial.faceTarget(remote.operationSelection()) : null;
      var guides = input.feedback().faces().capture(now, now, sessions.currentScope(), faceValue(hit),
         faceValue(workspaceFace == null ? null : workspaceFace.hit()), workspaceFace == null ? null : workspaceFace.partId());
      var selection = new SelectionPreviewInput(previews.operation(), pointer, gizmo, dragged, fallback,
         rayHit(guides.operation()), rayHit(guides.workspace()), serverSelection, operations.active());
      operations.pointer().prune(operations.workspace().parts());
      var hud = ClientHudCapture.capture(minecraft, geometry == null ? null : geometry.plan(), gizmo, now, now, visible);
      boolean localSelection = operations.active() || operations.selectionEdits().selectionDraftActive();
      var observation = new PreviewObservation(input.smartFill().active(), QuickReplaceMode.active(),
         sessions.modeView().idleActionsAllowed(), previews.geometry().active(), previews.building().active(),
         previews.operation().active() || localSelection, pointer instanceof OperationInteractionIntent.CreateSelection);
      var mode = ClientModes.registry().preview(observation);
      var selectionLayer = minecraft.player == null || minecraft.level == null ? null : PreviewLayer.capture(system.selection(), selection);
      var world = ClientWorldPreviewCapture.capture(minecraft, mode, geometry, selectionLayer);
      system.publish(new PreviewFrame(now, PreviewAnimation.current(), VisualThemes.revision(), reticle, hud, mode, world, Optional.ofNullable(selectionLayer),
         ClientWorldPreviewCapture.blueOutline(minecraft, observation.idle(), observation.selection(), observation.geometry(), observation.selectionCandidate())));
   }
   private static SelectionFaceFeedback.Face faceValue(OperationGeometry.RayHit hit) {
      return hit == null ? null : new SelectionFaceFeedback.Face(hit.point(), hit.normal(), hit.distance(), hit.axis());
   }
   private static OperationGeometry.RayHit rayHit(SelectionFaceFeedback.Face hit) {
      return hit == null ? null : new OperationGeometry.RayHit(hit.point(), hit.normal(), hit.distance(), hit.axis());
   }
}
