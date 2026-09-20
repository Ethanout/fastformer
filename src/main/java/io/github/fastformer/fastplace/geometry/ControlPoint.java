package io.github.fastformer.fastplace.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public record ControlPoint(
   Vec3 center,
   ControlPointShape shape,
   ControlPointRole role,
   ControlPointState state,
   ControlPointFeedback feedback,
   boolean hovered
) {
   public ControlPoint {
      center = GeometryNumbers.finiteOrZero(center);
      shape = shape == null ? ControlPointShape.at(center) : shape;
      role = role == null ? ControlPointRole.SECONDARY : role;
      state = state == null ? ControlPointState.CONFIRMED : state;
      feedback = feedback == null ? ControlPointFeedback.none() : feedback;
   }

   public static ControlPoint of(BlockPos pos, ControlPointRole role) {
      return new ControlPoint(
         Vec3.atCenterOf(pos), ControlPointShape.BLOCK, role, ControlPointState.CONFIRMED, ControlPointFeedback.none(), false
      );
   }

   public static ControlPoint precise(Vec3 center, ControlPointRole role) {
      return new ControlPoint(
         center, ControlPointShape.at(center), role, ControlPointState.CONFIRMED, ControlPointFeedback.none(), false
      );
   }

   public static ControlPoint pending(Vec3 center, ControlPointRole role) {
      return new ControlPoint(
         center, ControlPointShape.at(center), role, ControlPointState.PENDING, ControlPointFeedback.none(), false
      );
   }

   public static ControlPoint derived(Vec3 center, ControlPointRole role) {
      return new ControlPoint(
         center, ControlPointShape.POINT, role, ControlPointState.DERIVED, ControlPointFeedback.none(), false
      );
   }

   public static ControlPoint primary(BlockPos pos, boolean hovered) {
      return new ControlPoint(
         Vec3.atCenterOf(pos),
         ControlPointShape.BLOCK,
         ControlPointRole.PRIMARY,
         ControlPointState.CONFIRMED,
         ControlPointFeedback.closeable(),
         hovered
      );
   }

   public static ControlPoint secondary(BlockPos pos) {
      return secondary(pos, false);
   }

   public static ControlPoint secondary(BlockPos pos, boolean hovered) {
      return new ControlPoint(
         Vec3.atCenterOf(pos),
         ControlPointShape.BLOCK,
         ControlPointRole.SECONDARY,
         ControlPointState.CONFIRMED,
         ControlPointFeedback.none(),
         hovered
      );
   }

   public BlockPos pos() {
      return BlockPos.containing(this.center);
   }

   public Component hoverText() {
      String key = switch (this.role) {
         case PRIMARY -> "fastformer.operation.control_point.primary";
         case SECONDARY -> "fastformer.operation.control_point.secondary";
         case CENTER, BASE_CENTER, DERIVED_CENTER -> "fastformer.operation.control_point.center";
         case RADIUS -> "fastformer.operation.control_point.radius";
         case DIAMETER_A, DIAMETER_B -> "fastformer.operation.control_point.diameter";
         case BASE_FACE -> "fastformer.operation.control_point.base_face";
         case HEIGHT -> "fastformer.operation.control_point.height";
         case GIZMO_HANDLE -> "fastformer.operation.control_point.handle";
      };
      return Component.translatable(key);
   }

   public boolean confirmed() {
      return this.state.confirmed();
   }

   public ControlPoint withHovered(boolean hovered) {
      return hovered == this.hovered
         ? this
         : new ControlPoint(this.center, this.shape, this.role, this.state, this.feedback, hovered);
   }

   public ControlPoint withFeedback(ControlPointFeedback feedback) {
      ControlPointFeedback value = feedback == null ? ControlPointFeedback.none() : feedback;
      return value.equals(this.feedback)
         ? this
         : new ControlPoint(this.center, this.shape, this.role, this.state, value, this.hovered);
   }

   public ControlPoint withShape(ControlPointShape shape) {
      ControlPointShape value = shape == null ? ControlPointShape.at(this.center) : shape;
      return value == this.shape
         ? this
         : new ControlPoint(this.center, value, this.role, this.state, this.feedback, this.hovered);
   }
}
