package io.github.fastformer.common.interaction.binding;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoubleClickPolicyTest {
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final InteractionShapeSnapshot FULL = new InteractionShapeSnapshot(
      new BlockPos(-10, 4, 20), List.of(new AABB(0, 0, 0, 1, 1, 1)));

   @Test void exactHitLocationAndFaceAlwaysWinOverPaddedGeometry() {
      var eye = new Vec3(-9.01, 4.75, 0);
      var exact = FULL.clip(eye, VIEW);
      var hit = DoubleClickPolicy.DEFAULT.capture(FULL, eye, VIEW).clip(FULL, eye, VIEW);
      assertEquals(exact.getLocation(), hit.getLocation());
      assertEquals(exact.getDirection(), hit.getDirection());
      assertEquals(exact.isInside(), hit.isInside());
   }

   @Test void paddingIsDistanceBasedButNeverExceedsHalfABlock() {
      var near = DoubleClickPolicy.DEFAULT.capture(FULL, new Vec3(-9.5, 4.5, 6), VIEW);
      var far = DoubleClickPolicy.DEFAULT.capture(FULL, new Vec3(-9.5, 4.5, 0), VIEW);
      var remote = DoubleClickPolicy.DEFAULT.capture(FULL, new Vec3(-9.5, 4.5, -180), VIEW);
      assertEquals(0, near.shapePadding());
      assertTrue(far.shapePadding() > 0.13 && far.shapePadding() < 0.2);
      assertEquals(0.5, remote.shapePadding());
      assertNull(remote.clip(FULL, new Vec3(-8.49, 4.5, -180), VIEW));
   }

   @Test void paddingPreservesDisconnectedGeometryAndEmptyShapes() {
      var split = new InteractionShapeSnapshot(FULL.position(), List.of(
         new AABB(0, 0, 0, 0.125, 1, 1), new AABB(0.875, 0, 0, 1, 1, 1)));
      var eye = new Vec3(-9.95, 4.5, 0);
      var tolerance = DoubleClickPolicy.DEFAULT.capture(split, eye, VIEW);
      assertNull(tolerance.clip(split, new Vec3(-9.5, 4.5, 0), VIEW));
      assertNull(tolerance.clip(new InteractionShapeSnapshot(FULL.position(), List.of()), eye, VIEW));
   }

   @Test void configuredBandsAndAlternativesRoundTripAndRejectMissingDistanceRanges() {
      var source = com.google.gson.JsonParser.parseString("""
         {"tolerance":[{"below":10,"window_ms":125,"exact":true},
                       {"from":10,"window_ms":450,"edge_deg":1,"edge_max_blocks":0.25}],
          "accept_if_any":[{"view_change_deg":1},{"hit_distance_blocks":0.125}]}
         """);
      var policy = DoubleClickPolicy.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, source).getOrThrow();
      var encoded = DoubleClickPolicy.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, policy).getOrThrow();
      assertEquals(policy, DoubleClickPolicy.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow());
      assertEquals(450_000_000L, policy.capture(FULL, new Vec3(-9.5, 4.5, 0), VIEW).windowNanos());
      var invalid = source.deepCopy().getAsJsonObject();
      invalid.getAsJsonArray("tolerance").get(1).getAsJsonObject().addProperty("from", 11);
      assertTrue(DoubleClickPolicy.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, invalid).error().isPresent());
   }
}
