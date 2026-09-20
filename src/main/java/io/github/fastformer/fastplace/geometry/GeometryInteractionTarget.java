package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public record GeometryInteractionTarget(
   TargetType type,
   int index,
   Vec3 center,
   Vec3 halfExtents,
   PointerInteraction interaction
) {
   public GeometryInteractionTarget {
      type = type == null ? TargetType.CONTROL_POINT : type;
      center = GeometryNumbers.finiteOrZero(center);
      halfExtents = sanitizeHalfExtents(halfExtents);
      interaction = interaction == null ? PointerInteraction.empty() : interaction;
   }

   public static GeometryInteractionTarget controlPoint(int index, Vec3 center) {
      Vec3 safeCenter = GeometryNumbers.finiteOrZero(center);
      return new GeometryInteractionTarget(
         TargetType.CONTROL_POINT,
         index,
         safeCenter,
         ControlPointShape.at(safeCenter).halfExtents(),
         PointerInteraction.selectControlPoint()
      );
   }

   public static GeometryInteractionTarget closePath(int index, Vec3 center) {
      Vec3 safeCenter = GeometryNumbers.finiteOrZero(center);
      return new GeometryInteractionTarget(
         TargetType.CLOSE_PATH,
         index,
         Vec3.atCenterOf(BlockPos.containing(safeCenter)),
         new Vec3(0.5, 0.5, 0.5),
         PointerInteraction.closePath()
      );
   }

   public AABB bounds(double padding) {
      double pad = Math.max(0.0, GeometryNumbers.finiteOr(padding, 0.0));
      return new AABB(
         this.center.x - this.halfExtents.x - pad,
         this.center.y - this.halfExtents.y - pad,
         this.center.z - this.halfExtents.z - pad,
         this.center.x + this.halfExtents.x + pad,
         this.center.y + this.halfExtents.y + pad,
         this.center.z + this.halfExtents.z + pad
      );
   }

   public GeometryInteractionAction action(PointerGesture gesture) {
      return this.interaction == null ? null : this.interaction.action(gesture);
   }

   /** Text shown beside the crosshair for this geometry target. */
   public Component hoverText() {
      return switch (this.type) {
         case CONTROL_POINT -> Component.translatable("fastformer.operation.control_point.handle");
         case CLOSE_PATH -> Component.translatable("fastformer.operation.selection_click_hint");
      };
   }

   /** Enforced entry point for renderers and input feedback. */
   public Component requireHoverText() {
      Component text = hoverText();
      if (text == null) {
         throw new IllegalStateException("Geometry interaction target is missing hover text: " + this.type);
      }
      return text;
   }

   private static Vec3 sanitizeHalfExtents(Vec3 value) {
      if (value == null) {
         return Vec3.ZERO;
      }
      return new Vec3(
         Math.max(0.0, GeometryNumbers.finiteOr(value.x, 0.0)),
         Math.max(0.0, GeometryNumbers.finiteOr(value.y, 0.0)),
         Math.max(0.0, GeometryNumbers.finiteOr(value.z, 0.0))
      );
   }

   public enum TargetType {
      CONTROL_POINT,
      CLOSE_PATH
   }
}
