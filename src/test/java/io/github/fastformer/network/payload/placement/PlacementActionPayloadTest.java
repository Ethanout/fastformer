package io.github.fastformer.network.payload.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PlacementActionPayloadTest {
   @Test
   void nullActionUsesTheSafeConfirmDefault() {
      assertEquals(PlacementActionPayload.Action.CONFIRM, new PlacementActionPayload(null).action());
   }
   @Test
   void codecPreservesSemanticAction() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      PlacementActionPayload.STREAM_CODEC.encode(
         buffer,
         new PlacementActionPayload(PlacementActionPayload.Action.QUICK_SHAPE, 42L)
      );

      PlacementActionPayload decoded = PlacementActionPayload.STREAM_CODEC.decode(buffer);
      assertEquals(PlacementActionPayload.Action.QUICK_SHAPE, decoded.action());
      assertEquals(42L, decoded.requestId());
   }

   @Test
   void codecRejectsUnknownProtocolVersion() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      buffer.writeVarInt(PlacementActionPayload.PROTOCOL_VERSION + 1);
      assertThrows(IllegalArgumentException.class, () -> PlacementActionPayload.STREAM_CODEC.decode(buffer));
   }

   @Test
   void payloadDoesNotContainShapeOrBlockData() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      PlacementActionPayload.STREAM_CODEC.encode(
         buffer,
         new PlacementActionPayload(PlacementActionPayload.Action.CONFIRM)
      );

      assertEquals(3, buffer.readableBytes());
   }

   @Test
   void requestIdMustBeNonNegative() {
      assertThrows(
         IllegalArgumentException.class,
         () -> new PlacementActionPayload(PlacementActionPayload.Action.CONFIRM, -1L)
      );
   }
}
