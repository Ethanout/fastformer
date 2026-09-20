package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryInteractionTargetTest {

   @Test
   void everyBuiltInTargetProvidesHoverText() {
      assertInstanceOf(net.minecraft.network.chat.Component.class,
         GeometryInteractionTarget.controlPoint(0, new Vec3(0.5, 1.5, 2.5)).requireHoverText());
      assertInstanceOf(net.minecraft.network.chat.Component.class,
         GeometryInteractionTarget.closePath(0, new Vec3(0.5, 1.5, 2.5)).requireHoverText());
   }

   @Test
   void hitDelegatesHoverTextToResolvedTarget() {
      GeometryInteractionTarget target = GeometryInteractionTarget.controlPoint(0, new Vec3(0.5, 0.5, 0.5));
      GeometryInteractionHit hit = GeometryInteractionHit.from(
         new Vec3(0.5, 0.5, -2.0), new Vec3(0.0, 0.0, 1.0), 8.0, target);
      assertInstanceOf(net.minecraft.network.chat.Component.class, hit.hoverText());
   }
   @Test
   void selectControlPointBindsOnlySingleClicksAndDoesNotMutateSource() {
      PointerInteraction source = PointerInteraction.empty();
      PointerInteraction interaction = source.bindLeftClick(GeometryInteractionAction.SELECT_CONTROL_POINT)
         .bindRightClick(GeometryInteractionAction.SELECT_CONTROL_POINT);

      assertNull(source.action(PointerGesture.LEFT_CLICK));
      assertSame(GeometryInteractionAction.SELECT_CONTROL_POINT, interaction.action(PointerGesture.LEFT_CLICK));
      assertSame(GeometryInteractionAction.SELECT_CONTROL_POINT, interaction.action(PointerGesture.RIGHT_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.RIGHT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_LONG_PRESS));
      assertNull(interaction.action(PointerGesture.RIGHT_LONG_PRESS));
   }

   @Test
   void selectControlPointFactoryBindsBothSingleClicks() {
      PointerInteraction interaction = PointerInteraction.selectControlPoint();

      assertSame(GeometryInteractionAction.SELECT_CONTROL_POINT, interaction.action(PointerGesture.LEFT_CLICK));
      assertSame(GeometryInteractionAction.SELECT_CONTROL_POINT, interaction.action(PointerGesture.RIGHT_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.RIGHT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_LONG_PRESS));
      assertNull(interaction.action(PointerGesture.RIGHT_LONG_PRESS));
   }

   @Test
   void closePathFactoryBindsOnlyRightClicks() {
      PointerInteraction interaction = PointerInteraction.closePath();

      assertSame(GeometryInteractionAction.CLOSE_PATH, interaction.action(PointerGesture.RIGHT_CLICK));
      assertSame(GeometryInteractionAction.CLOSE_PATH, interaction.action(PointerGesture.RIGHT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_DOUBLE_CLICK));
      assertNull(interaction.action(PointerGesture.LEFT_LONG_PRESS));
      assertNull(interaction.action(PointerGesture.RIGHT_LONG_PRESS));
   }

   @Test
   void targetBoundsIncludePaddingAndClampInvalidPadding() {
      GeometryInteractionTarget target = new GeometryInteractionTarget(
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         2,
         new Vec3(4.5, 5.5, 6.5),
         new Vec3(0.25, 0.5, 0.75),
         PointerInteraction.selectControlPoint()
      );

      AABB zero = target.bounds(0.0);
      AABB padded = target.bounds(0.25);
      AABB negative = target.bounds(-2.0);
      AABB nan = target.bounds(Double.NaN);

      assertEquals(4.25, zero.minX, 1.0E-9);
      assertEquals(5.0, zero.minY, 1.0E-9);
      assertEquals(5.75, zero.minZ, 1.0E-9);
      assertEquals(4.75, zero.maxX, 1.0E-9);
      assertEquals(6.0, zero.maxY, 1.0E-9);
      assertEquals(7.25, zero.maxZ, 1.0E-9);
      assertEquals(4.0, padded.minX, 1.0E-9);
      assertEquals(4.75, padded.minY, 1.0E-9);
      assertEquals(5.5, padded.minZ, 1.0E-9);
      assertEquals(5.0, padded.maxX, 1.0E-9);
      assertEquals(6.25, padded.maxY, 1.0E-9);
      assertEquals(7.5, padded.maxZ, 1.0E-9);
      assertEquals(zero.minX, negative.minX, 1.0E-9);
      assertEquals(zero.minY, negative.minY, 1.0E-9);
      assertEquals(zero.minZ, negative.minZ, 1.0E-9);
      assertEquals(zero.maxX, negative.maxX, 1.0E-9);
      assertEquals(zero.maxY, negative.maxY, 1.0E-9);
      assertEquals(zero.maxZ, negative.maxZ, 1.0E-9);
      assertEquals(zero.minX, nan.minX, 1.0E-9);
      assertEquals(zero.maxZ, nan.maxZ, 1.0E-9);
   }

   @Test
   void nearestHitWinsOverFartherTarget() {
      GeometryInteractionTarget far = target(8.0);
      GeometryInteractionTarget near = target(4.0);

      GeometryInteractionHit hit = GeometryInteractionHit.nearest(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), 16.0, List.of(far, near));

      assertEquals(near, hit.target());
      assertEquals(3.5, hit.rayDistance(), 1.0E-9);
      assertEquals(new Vec3(0.0, 0.0, 3.5), hit.point());
   }

   @Test
   void singleTargetHitRejectsInvalidRaysAndOverreach() {
      GeometryInteractionTarget target = target(4.0);

      assertNull(GeometryInteractionHit.from(Vec3.ZERO, Vec3.ZERO, 16.0, target));
      assertNull(GeometryInteractionHit.from(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), Double.NaN, target));
      assertNull(GeometryInteractionHit.from(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), -1.0, target));
      assertNull(GeometryInteractionHit.from(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), 3.0, target));
      assertNull(GeometryInteractionHit.from(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 16.0, target));
   }

   @Test
   void closePathTargetCanExposeDedicatedAction() {
      GeometryInteractionTarget target = new GeometryInteractionTarget(
         GeometryInteractionTarget.TargetType.CLOSE_PATH,
         -1,
         new Vec3(1.0, 1.0, 1.0),
         new Vec3(0.25, 0.25, 0.25),
         PointerInteraction.closePath()
      );

      assertSame(GeometryInteractionAction.CLOSE_PATH, target.action(PointerGesture.RIGHT_CLICK));
      assertSame(GeometryInteractionAction.CLOSE_PATH, target.action(PointerGesture.RIGHT_DOUBLE_CLICK));
      assertTrue(target.action(PointerGesture.LEFT_CLICK) == null);
   }

   @Test
   void degenerateHalfExtentsStillAllowExactRayHits() {
      GeometryInteractionTarget target = new GeometryInteractionTarget(
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         1,
         new Vec3(0.0, 0.0, 4.5),
         Vec3.ZERO,
         PointerInteraction.selectControlPoint()
      );

      GeometryInteractionHit hit = GeometryInteractionHit.from(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), 16.0, target);

      assertNotNull(hit);
      assertEquals(4.5, hit.rayDistance(), 1.0E-9);
      assertEquals(new Vec3(0.0, 0.0, 4.5), hit.point());
   }

   @Test
   void semanticFactoriesBindTheExpectedTargetActions() {
      GeometryInteractionTarget point = GeometryInteractionTarget.controlPoint(2, new Vec3(1.5, 2.5, 3.5));
      GeometryInteractionTarget close = GeometryInteractionTarget.closePath(0, new Vec3(1.5, 2.5, 3.5));

      assertEquals(GeometryInteractionTarget.TargetType.CONTROL_POINT, point.type());
      assertEquals(GeometryInteractionAction.SELECT_CONTROL_POINT, point.action(PointerGesture.LEFT_CLICK));
      assertEquals(GeometryInteractionAction.SELECT_CONTROL_POINT, point.action(PointerGesture.RIGHT_CLICK));
      assertEquals(GeometryInteractionTarget.TargetType.CLOSE_PATH, close.type());
      assertEquals(GeometryInteractionAction.CLOSE_PATH, close.action(PointerGesture.RIGHT_CLICK));
      assertEquals(GeometryInteractionAction.CLOSE_PATH, close.action(PointerGesture.RIGHT_DOUBLE_CLICK));
      assertEquals(new Vec3(1.5, 2.5, 3.5), close.center());
      assertEquals(new Vec3(0.5, 0.5, 0.5), close.halfExtents());
   }

   private static GeometryInteractionTarget target(double z) {
      return new GeometryInteractionTarget(
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         0,
         new Vec3(0.0, 0.0, z),
         new Vec3(0.5, 0.5, 0.5),
         PointerInteraction.selectControlPoint()
      );
   }
}
