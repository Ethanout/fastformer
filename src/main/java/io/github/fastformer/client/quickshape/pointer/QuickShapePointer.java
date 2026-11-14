package io.github.fastformer.client.quickshape.pointer;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.QuickShapeInputRules;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** One quick-shape owner's pointing and placement context, independent of drawing. */
public final class QuickShapePointer {
   private final Supplier<BuildingPreviewSnapshot> preview;
   private final Supplier<InteractionContext> context;
   private final Function<InteractionContext, LongRangeBlockRaycast.Result> raycast;

   public QuickShapePointer(Supplier<BuildingPreviewSnapshot> preview, Supplier<InteractionContext> context,
      Function<InteractionContext, LongRangeBlockRaycast.Result> raycast) {
      this.preview = Objects.requireNonNull(preview, "preview");
      this.context = Objects.requireNonNull(context, "context");
      this.raycast = Objects.requireNonNull(raycast, "raycast");
   }

   public BlockPos candidate() { return candidate(preview.get()); }

   public BlockPos candidate(BuildingPreviewSnapshot snapshot) {
      var c = context.get();
      if (c == null) return null;
      var hit = raycast.apply(c).hit();
      if (!snapshot.active() && snapshot.points().isEmpty()) {
         if (hit.getType() != Type.BLOCK || c.player() == null) return null;
         if (c.player().getMainHandItem().isEmpty() || c.alternative()) return hit.getBlockPos();
         return capturePlacement(c, hit, false).placementPosition();
      }
      return resolveCandidate(snapshot, c.eye(), c.view(), hit, c.alternative());
   }

   public BlockPos lineCandidate() {
      var snapshot = preview.get();
      return snapshot.active() && QuickShapeStage.fromPointCount(snapshot.points().size()) == QuickShapeStage.LINE ? candidate(snapshot) : null;
   }

   public PlacementContextSnapshot placementContext(BuildingPreviewSnapshot snapshot) {
      if (snapshot.placementContext() != null || !snapshot.points().isEmpty()) return snapshot.placementContext();
      var c = context.get();
      if (c == null || c.player() == null) return null;
      var hit = raycast.apply(c).hit();
      if (hit.getType() != Type.BLOCK) return null;
      boolean embedded = snapshot.pointMode() == PointMode.RAYCAST
         && QuickShapeModes.effective(snapshot, c.alternative()).raycastPlacement() == RaycastPlacement.EMBEDDED;
      return capturePlacement(c, hit, embedded);
   }

   public List<BlockPos> openPath() {
      var p = preview.get();
      return p.active() && p.faceMode() == FaceMode.POLYGON && !p.polygonClosed() ? p.points() : List.of();
   }

   public boolean middleIgnored() {
      var p = preview.get();
      return QuickShapeInputRules.ignoresMiddleClick(p.faceMode(), p.points().size(), p.polygonClosed());
   }

   public boolean raycastSubmodeAvailable(boolean otherSession) {
      var p = preview.get();
      if (!p.enabled() || otherSession || !QuickShapeModes.raycastSubmode(p)) return false;
      if (p.active()) return true;
      var c = context.get();
      return c != null && c.player() != null
         && io.github.fastformer.fastplace.placement.context.PlaceableItems.isPlaceable(c.player().getMainHandItem());
   }

   static BlockPos resolveCandidate(BuildingPreviewSnapshot p, Vec3 eye, Vec3 view, BlockHitResult hit, boolean modifier) {
      if (p.polygonHeightConfirmed()) return p.points().isEmpty() ? null : p.points().getLast();
      BlockPos hitBlock;
      BlockPos surfaceBlock;
      if (hit.getType() == Type.BLOCK) {
         hitBlock = hit.getBlockPos();
         surfaceBlock = hitBlock.relative(hit.getDirection());
      } else {
         var base = p.points().isEmpty() ? BlockPos.ZERO : p.points().getFirst();
         hitBlock = base.offset(p.freeScrollOffset());
         surfaceBlock = hitBlock;
      }
      return FastPlaceGeometry.resolveCandidate(p.points(), p.polygonClosed(), hitBlock, surfaceBlock,
         p.faceBaseOffset(), p.volumeBaseOffset(), p.perpendicularAnchor(), eye, view, p.freeScrollOffset(), QuickShapeModes.effective(p, modifier));
   }

   private static PlacementContextSnapshot capturePlacement(InteractionContext c, BlockHitResult hit, boolean embedded) {
      return PlacementContextSnapshot.capture(c.player().level(), c.player(), c.player().getMainHandItem(), hit, embedded);
   }
}
