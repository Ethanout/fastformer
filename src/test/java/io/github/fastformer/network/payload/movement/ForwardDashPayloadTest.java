package io.github.fastformer.network.payload.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ForwardDashPayloadTest {
   @Test
   void authorizationPreservesTheRequestAndWorldIdentityAndSafeEndpoint() {
      var request = new ForwardDashPayload(37, 200);
      var response = new ForwardDashStartPayload(37, ResourceLocation.withDefaultNamespace("overworld"), 81,
         new Vec3(12, 70, -4), new Vec3(212, 70, -4));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         ForwardDashPayload.STREAM_CODEC.encode(buffer, request);
         assertEquals(request, ForwardDashPayload.STREAM_CODEC.decode(buffer));
         ForwardDashStartPayload.STREAM_CODEC.encode(buffer, response);
         assertEquals(response, ForwardDashStartPayload.STREAM_CODEC.decode(buffer));
      } finally {
         buffer.release();
      }
   }

   @Test
   void invalidOrUnboundedPathsAreRejected() {
      var dimension = ResourceLocation.withDefaultNamespace("overworld");
      assertThrows(IllegalArgumentException.class, () -> new ForwardDashPayload(0, 15));
      assertThrows(IllegalArgumentException.class, () -> new ForwardDashPayload(1, -1));
      assertThrows(IllegalArgumentException.class, () -> new ForwardDashPayload(1, 201));
      assertThrows(IllegalArgumentException.class,
         () -> new ForwardDashStartPayload(1, dimension, 1, Vec3.ZERO, new Vec3(201, 0, 0)));
      assertThrows(IllegalArgumentException.class,
         () -> new ForwardDashStartPayload(1, dimension, 1, Vec3.ZERO, new Vec3(Double.NaN, 0, 0)));
   }

   @Test
   void zeroDistanceCanBeAcknowledgedWithoutMoving() {
      var request = new ForwardDashPayload(1, 0);
      var response = new ForwardDashStartPayload(1, ResourceLocation.withDefaultNamespace("overworld"), 1, Vec3.ZERO, Vec3.ZERO);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         ForwardDashPayload.STREAM_CODEC.encode(buffer, request);
         assertEquals(request, ForwardDashPayload.STREAM_CODEC.decode(buffer));
         ForwardDashStartPayload.STREAM_CODEC.encode(buffer, response);
         assertEquals(response, ForwardDashStartPayload.STREAM_CODEC.decode(buffer));
      } finally {
         buffer.release();
      }
   }
}
