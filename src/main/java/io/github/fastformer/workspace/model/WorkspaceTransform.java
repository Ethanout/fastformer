package io.github.fastformer.workspace.model;

import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Immutable local transform for one client-side operation part. */
public record WorkspaceTransform(
   Vec3 translation,
   Vec3 rotation,
   OperationStackRegion repeats,
   BlockPos repeatStride,
   Vec3 scale,
   WorkspaceSymmetry symmetry
) {
   public static final WorkspaceTransform IDENTITY = new WorkspaceTransform(
      Vec3.ZERO, Vec3.ZERO, OperationStackRegion.origin(), BlockPos.ZERO, new Vec3(1.0, 1.0, 1.0)
   );

   public WorkspaceTransform(Vec3 translation, Vec3 rotation, OperationStackRegion repeats) {
      this(translation, rotation, repeats, BlockPos.ZERO);
   }

   public WorkspaceTransform(Vec3 translation, Vec3 rotation, OperationStackRegion repeats, BlockPos repeatStride) {
      this(translation, rotation, repeats, repeatStride, new Vec3(1.0, 1.0, 1.0));
   }

   public WorkspaceTransform(Vec3 translation, Vec3 rotation, OperationStackRegion repeats, BlockPos repeatStride, Vec3 scale) {
      this(translation, rotation, repeats, repeatStride, scale, WorkspaceSymmetry.IDENTITY);
   }

   public WorkspaceTransform {
      translation = translation == null ? Vec3.ZERO : translation;
      rotation = rotation == null ? Vec3.ZERO : rotation;
      repeats = repeats == null ? OperationStackRegion.origin() : repeats;
      repeatStride = repeatStride == null ? BlockPos.ZERO : repeatStride.immutable();
      scale = scale == null ? new Vec3(1.0, 1.0, 1.0) : scale;
      symmetry = symmetry == null ? WorkspaceSymmetry.IDENTITY : symmetry;
   }

   /** Whether this transform changes the composed voxel positions. */
   public boolean hasEffect() {
      return this.hasBaseEffect()
         || !this.repeats.equals(OperationStackRegion.origin());
   }

   /** Whether the original source footprint is displaced or reshaped. */
   public boolean hasBaseEffect() {
      return !this.translation.equals(Vec3.ZERO)
         || !this.rotation.equals(Vec3.ZERO)
         || !this.scale.equals(new Vec3(1.0, 1.0, 1.0))
         || !this.symmetry.equals(WorkspaceSymmetry.IDENTITY);
   }

   public boolean isIdentity() {
      return !this.hasEffect();
   }

   /** Returns the same transform with repetitions removed for base-preview calculations. */
   public WorkspaceTransform withoutRepeats() {
      return new WorkspaceTransform(
         this.translation, this.rotation, OperationStackRegion.origin(), this.repeatStride, this.scale, this.symmetry
      );
   }

   public WorkspaceTransform withTranslation(Vec3 value) {
      return new WorkspaceTransform(value, this.rotation, this.repeats, this.repeatStride, this.scale, this.symmetry);
   }

   public WorkspaceTransform withRotation(Vec3 value) {
      return new WorkspaceTransform(this.translation, value, this.repeats, this.repeatStride, this.scale, this.symmetry);
   }

   public WorkspaceTransform withRepeats(OperationStackRegion value, BlockPos stride) {
      return new WorkspaceTransform(this.translation, this.rotation, value, stride, this.scale, this.symmetry);
   }

   public WorkspaceTransform withRepeatStride(AxisGizmo.Axis axis, int value) {
      BlockPos stride = switch (axis) {
         case X -> new BlockPos(value, this.repeatStride.getY(), this.repeatStride.getZ());
         case Y -> new BlockPos(this.repeatStride.getX(), value, this.repeatStride.getZ());
         case Z -> new BlockPos(this.repeatStride.getX(), this.repeatStride.getY(), value);
      };
      return new WorkspaceTransform(this.translation, this.rotation, this.repeats, stride, this.scale, this.symmetry);
   }

   public WorkspaceTransform withScale(AxisGizmo.Axis axis, double value) {
      Vec3 updated = switch (axis) {
         case X -> new Vec3(value, this.scale.y, this.scale.z);
         case Y -> new Vec3(this.scale.x, value, this.scale.z);
         case Z -> new Vec3(this.scale.x, this.scale.y, value);
      };
      return new WorkspaceTransform(this.translation, this.rotation, this.repeats, this.repeatStride, updated, this.symmetry);
   }

   public WorkspaceTransform withSymmetry(WorkspaceSymmetry value) {
      return new WorkspaceTransform(this.translation, this.rotation, this.repeats, this.repeatStride, this.scale, value);
   }

   /** Final signed-axis transform aligned exactly as voxel-centre sampling does. */
   public Vec3 voxelPoint(Vec3 point) {
      Vec3 offset = this.symmetry.offset().add(this.translation);
      return this.symmetry.vector(point).add(
         Math.floor(offset.x + 0.5), Math.floor(offset.y + 0.5), Math.floor(offset.z + 0.5));
   }
}
