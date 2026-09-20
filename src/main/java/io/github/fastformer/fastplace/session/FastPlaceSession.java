package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.quickshape.FastPlaceStage;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class FastPlaceSession implements SessionLifecycle {
   private final List<BlockPos> points = new ArrayList<>();
   private final SessionValue<Vec3> faceBaseOffset = new SessionValue<>(() -> Vec3.ZERO, SessionValue.ResetOn.STAGE_CHANGE, SessionValue.ResetOn.DESTROY);
   private final SessionValue<Vec3> volumeBaseOffset = new SessionValue<>(() -> Vec3.ZERO, SessionValue.ResetOn.STAGE_CHANGE, SessionValue.ResetOn.DESTROY);
   private final SessionValue<BlockPos> freeScrollOffset = new SessionValue<>(() -> BlockPos.ZERO, SessionValue.ResetOn.STAGE_CHANGE, SessionValue.ResetOn.DESTROY);
   private boolean modifierHeld;
   private BlockPos perpendicularAnchor;
   private boolean polygonClosed;
   private boolean polygonHeightConfirmed;
   private PolygonVolumeShape polygonVolumeShape = PolygonVolumeShape.EXTRUDE;
   private LineTieBias faceTieBias = LineTieBias.DEFAULT;
   private PlacementContextSnapshot placementContext;

   public List<BlockPos> points() {
      return Collections.unmodifiableList(this.points);
   }

   public FastPlaceStage stage() {
      return FastPlaceGeometry.stageFor(this.points);
   }

   public Vec3 faceBaseOffset() {
      return this.faceBaseOffset.get();
   }

   public Vec3 volumeBaseOffset() {
      return this.volumeBaseOffset.get();
   }

   public BlockPos perpendicularAnchor() {
      return this.perpendicularAnchor;
   }

   public boolean modifierHeld() {
      return this.modifierHeld;
   }

   public BlockPos freeScrollOffset() {
      return this.freeScrollOffset.get();
   }

   public boolean polygonClosed() {
      return this.polygonClosed;
   }

   public void closePolygon() {
      this.polygonClosed = true;
   }

   public boolean polygonHeightConfirmed() {
      return this.polygonHeightConfirmed;
   }

   public void confirmPolygonHeight() {
      this.polygonHeightConfirmed = true;
   }

   public PolygonVolumeShape polygonVolumeShape() {
      return this.polygonVolumeShape;
   }

   public LineTieBias faceTieBias() {
      return this.faceTieBias;
   }

   public PlacementContextSnapshot placementContext() {
      return this.placementContext;
   }

   public void capturePlacementContext(PlacementContextSnapshot value) {
      if (this.placementContext == null) {
         this.placementContext = value;
      }
   }

   public LineTieBias effectiveFaceTieBias(boolean modifierHeld) {
      return modifierHeld ? LineTieBias.OPPOSITE : this.faceTieBias;
   }

   public void confirmFaceTieBias(LineTieBias value) {
      this.faceTieBias = value == null ? LineTieBias.DEFAULT : value;
   }

   public void cyclePolygonVolumeShape() {
      this.polygonVolumeShape = this.polygonVolumeShape.next();
   }

   public void adjustFreeScrollOffset(Vec3 view, int steps) {
      if (steps == 0) {
         return;
      }
      Vec3 dominant = dominantAxis(view);
      int signedStep = Integer.signum(steps);
      BlockPos offset = this.freeScrollOffset.get();
      BlockPos newOffset = new BlockPos(
         offset.getX() + (int)(dominant.x * signedStep),
         offset.getY() + (int)(dominant.y * signedStep),
         offset.getZ() + (int)(dominant.z * signedStep)
      );
      this.freeScrollOffset.set(clampOffset(newOffset));
   }

   private static Vec3 dominantAxis(Vec3 view) {
      double x = Math.abs(view.x);
      double y = Math.abs(view.y);
      double z = Math.abs(view.z);
      if (x >= y && x >= z) {
         return new Vec3(Math.signum(view.x), 0.0, 0.0);
      } else if (y >= z) {
         return new Vec3(0.0, Math.signum(view.y), 0.0);
      } else {
         return new Vec3(0.0, 0.0, Math.signum(view.z));
      }
   }

   public void setFreeScrollOffset(BlockPos offset) {
      this.freeScrollOffset.set(clampOffset(offset));
   }

   public List<BlockPos> submissionPoints(LineMode lineMode) {
      return io.github.fastformer.fastplace.quickshape.QuickShapeSubmissionPoints.capture(
         this.points, lineMode, this.freeScrollOffset()
      );
   }

   public void setModifierHeld(boolean modifierHeld) {
      this.modifierHeld = modifierHeld;
   }

   public void onModeChanged() {
      this.onModeChanged(null);
   }

   /** Clears the old mode state, then optionally seeds a new free-scroll candidate. */
   public void onModeChanged(BlockPos freeScrollCandidateOffset) {
      this.polygonClosed = false;
      this.polygonHeightConfirmed = false;
      this.polygonVolumeShape = PolygonVolumeShape.EXTRUDE;
      this.faceTieBias = LineTieBias.DEFAULT;
      this.faceBaseOffset.reset(SessionValue.ResetOn.MODE_CHANGE);
      this.volumeBaseOffset.reset(SessionValue.ResetOn.MODE_CHANGE);
      this.freeScrollOffset.reset(SessionValue.ResetOn.MODE_CHANGE);
      if (freeScrollCandidateOffset != null) {
         this.setFreeScrollOffset(freeScrollCandidateOffset);
      }
   }

   public void adjustFaceBaseOffset(Vec3 axis, int steps) {
      Vec3 direction = axis.normalize();
      double value = Math.clamp(this.faceBaseOffset.get().dot(direction) + (double)Integer.signum(steps), -128.0, 128.0);
      this.faceBaseOffset.set(direction.scale(value));
   }

   public void adjustVolumeBaseOffset(Vec3 axis, int steps) {
      this.volumeBaseOffset.set(clampOffset(this.volumeBaseOffset.get().add(axis.scale((double)Integer.signum(steps)))));
   }

   public void setFaceBaseOffset(Vec3 axis, double value) {
      this.faceBaseOffset.set(axis.normalize().scale(Math.clamp(value, -128.0, 128.0)));
   }

   public void setVolumeBaseOffset(Vec3 offset) {
      this.volumeBaseOffset.set(clampOffset(offset));
   }

   private static Vec3 clampOffset(Vec3 offset) {
      return new Vec3(Math.clamp(offset.x, -128.0, 128.0), Math.clamp(offset.y, -128.0, 128.0), Math.clamp(offset.z, -128.0, 128.0));
   }

   private static BlockPos clampOffset(BlockPos offset) {
      return new BlockPos(Math.clamp(offset.getX(), -128, 128), Math.clamp(offset.getY(), -128, 128), Math.clamp(offset.getZ(), -128, 128));
   }

   public boolean addOrClose(BlockPos point, Vec3 eye, Vec3 view) {
      return this.addOrClose(point, eye, view, this.minimumClosingPoints());
   }

   public boolean addOrClose(BlockPos point, Vec3 eye, Vec3 view, int minimumClosingPoints) {
      if (this.points.size() >= minimumClosingPoints && this.points.getFirst().equals(point)) {
         return true;
      }
      this.addPoint(point, eye, view);
      return false;
   }

   public void addPoint(BlockPos point, Vec3 eye, Vec3 view) {
      if (this.points.isEmpty() || !this.points.getLast().equals(point)) {
         this.points.add(point.immutable());
         this.perpendicularAnchor = closestPointToRay(this.points, eye, view);
      }
   }

   private static BlockPos closestPointToRay(List<BlockPos> points, Vec3 eye, Vec3 view) {
      Vec3 direction = view.normalize();
      BlockPos closest = points.getFirst();
      double bestDistance = Double.POSITIVE_INFINITY;
      for (BlockPos point : points) {
         Vec3 relative = Vec3.atCenterOf(point).subtract(eye);
         double rayDistance = Math.max(0.0, relative.dot(direction));
         double distance = eye.add(direction.scale(rayDistance)).distanceToSqr(Vec3.atCenterOf(point));
         if (distance < bestDistance) {
            bestDistance = distance;
            closest = point;
         }
      }
      return closest;
   }

   @Override
   public boolean undoStep() {
      if (this.points.isEmpty()) {
         return false;
      }
      if (this.polygonClosed) {
         if (this.polygonHeightConfirmed) {
            this.polygonHeightConfirmed = false;
         } else {
            this.polygonClosed = false;
         }
      }
      this.points.removeLast();
      if (!this.points.contains(this.perpendicularAnchor)) {
         this.perpendicularAnchor = null;
      }
      this.onStageChanged();
      if (this.points.size() < 3) {
         this.faceTieBias = LineTieBias.DEFAULT;
      }
      return !this.points.isEmpty();
   }

   @Override
   public boolean canUndoStep() {
      return !this.points.isEmpty();
   }

   private int minimumClosingPoints() {
      return this.stage() == FastPlaceStage.VOLUME ? 4 : 3;
   }

   @Override
   public void onStageChanged() {
      this.faceBaseOffset.reset(SessionValue.ResetOn.STAGE_CHANGE);
      this.volumeBaseOffset.reset(SessionValue.ResetOn.STAGE_CHANGE);
      this.freeScrollOffset.reset(SessionValue.ResetOn.STAGE_CHANGE);
   }

   @Override
   public void onDestroyed() {
      this.polygonClosed = false;
      this.polygonHeightConfirmed = false;
      this.polygonVolumeShape = PolygonVolumeShape.EXTRUDE;
      this.faceTieBias = LineTieBias.DEFAULT;
      this.placementContext = null;
      this.faceBaseOffset.reset(SessionValue.ResetOn.DESTROY);
      this.volumeBaseOffset.reset(SessionValue.ResetOn.DESTROY);
      this.freeScrollOffset.reset(SessionValue.ResetOn.DESTROY);
   }

}
