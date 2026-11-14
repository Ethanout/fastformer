package io.github.fastformer.client.preview.assembly;

import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.quickshape.QuickShapePreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewModel;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.placement.context.PlaceableItems;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.client.preview.frame.PreviewLayer;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.mode.ModeId;
import java.util.Map;
import java.util.function.Supplier;
import io.github.fastformer.client.interaction.pointer.PointRaycast;
import io.github.fastformer.client.interaction.pointer.PointerRayPolicy;
import io.github.fastformer.common.raycast.InteractionRaycastRules;

/** Builds the selected world model at the visual boundary, before drawing starts. */
final class ClientWorldPreviewCapture {
   private static final double PREVIEW_REACH = LongRangeBlockRaycast.MAX_REACH;
   private ClientWorldPreviewCapture() {}
   private static ClientPreviewState previews() { return ClientSessionSystem.instance().previews(); }
   static Optional<PreviewLayer<?>> capture(Minecraft minecraft, Optional<ModeId> mode,
      GeometryPreviewModel geometry, PreviewLayer<?> selection) {
      if (minecraft.level == null || minecraft.player == null) return Optional.empty();
      var system = ClientPreviewSystem.instance();
      Map<ModeId, Supplier<PreviewLayer<?>>> builders = Map.of(
         BuiltinModes.SMART_FILL, () -> new PreviewLayer<>(system.fill(), system.fillFrame()),
         BuiltinModes.QUICK_REPLACE, () -> new PreviewLayer<>(system.replace(), system.replaceFrame()),
         BuiltinModes.IDLE, () -> new PreviewLayer<>(system.idle(), system.idleFrame()),
         BuiltinModes.GEOMETRY, () -> geometry == null ? null : new PreviewLayer<>(system.geometry(), geometry),
         BuiltinModes.QUICK_SHAPE, () -> quickShape(minecraft),
         BuiltinModes.SELECTION, () -> selection);
      return mode.flatMap(id -> {
         var builder = builders.get(id);
         if (builder == null) throw new IllegalStateException("No preview capability registered for " + id);
         return Optional.ofNullable(builder.get());
      });
   }
   private static PreviewLayer<?> quickShape(Minecraft minecraft) {
      var snapshot = previews().building();
      var player = minecraft.player;
      var pointer = ClientSessionSystem.instance().quickShapePointer();
      var candidate = pointer.candidate(snapshot);
      var hovered = snapshot.points().isEmpty() ? null : pointUnderCrosshair(List.of(snapshot.points().getFirst()));
      var context = pointer.placementContext(snapshot);
      var prototype = PlaceableItems.placementState(player.getMainHandItem(), player, context).orElse(null);
      return PreviewLayer.capture(ClientPreviewSystem.instance().quickShape(), new QuickShapePreviewInput(snapshot, player,
         player.getEyePosition(), player.getViewVector(1), candidate, hovered, prototype, context));
   }
   private static BlockPos pointUnderCrosshair(List<BlockPos> points) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null || points.isEmpty()) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      double maxDistance = PREVIEW_REACH;
      BlockHitResult worldHit = raycastBlocks(player);
      if (worldHit.getType() == Type.BLOCK) {
         maxDistance = Math.min(maxDistance, eye.distanceTo(worldHit.getLocation()) + 1.0E-4);
      }
      return PointRaycast.nearest(
         points, eye, player.getViewVector(1.0F), maxDistance);
   }

   private static BlockHitResult raycastBlocks(LocalPlayer player) {
      return blockPointer(player).hit();
   }

   private static LongRangeBlockRaycast.Result blockPointer(LocalPlayer player) {
      var operations = ClientSessionSystem.instance().operations();
      var hand = player.getMainHandItem();
      var policy = PointerRayPolicy.select(
         previews().geometry().active(), previews().building().active(), operations.active(),
         operations.selectionSessionActive() || operations.selectionEdits().selectionDraftActive(),
         hand.isEmpty(), PlaceableItems.isPlaceable(hand));
      var pointer = ClientSessionSystem.instance().inputSession().pointer();
      return pointer.sample(player, FastPlaceClientInput.modifierHeld(), policy);
   }

   static GeometryPreviewModel geometryPreviewModel() {
      var pointer = ClientSessionSystem.instance().geometryPointer();
      var plan = pointer.plan();
      var model = ClientPreviewSystem.instance().geometry().buildModel(new GeometryPreviewInput(previews().geometry(),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()), plan));
      return new GeometryPreviewModel(pointer.presentedPlan(), model.blocks());
   }

   static boolean blueOutline(Minecraft minecraft, boolean idle, boolean selection, boolean geometry, boolean candidate) {
      var player = minecraft.player;
      if (player == null || minecraft.level == null || QuickReplaceMode.active()) return false;
      if (idle) return ClientPreviewSystem.instance().idleFrame().hasOutline();
      var snapshot = previews().building();
      if (geometry || candidate || !snapshot.active() && selection) return true;
      if (InteractionContext.previewVisibility(minecraft) <= 0.01F) return false;
      if (snapshot.active()) return true;
      if (!snapshot.enabled() || !(player.getMainHandItem().isEmpty() || PlaceableItems.isPlaceable(player.getMainHandItem()))) return false;
      boolean modifier = PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession());
      var initial = InitialBlockPreview.resolve(player, raycastBlocks(player), modifier,
         InteractionRaycastRules.passesThroughFluids(player, modifier));
      return initial != null && !initial.mesh().edges().isEmpty();
   }
}
