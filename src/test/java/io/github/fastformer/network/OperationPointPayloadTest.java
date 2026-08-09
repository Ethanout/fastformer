package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationPointPayloadTest {
   @Test
   void codecCarriesOnlyTheRaycastIntent() {
      OperationPointPayload payload = new OperationPointPayload(OperationPointPayload.Role.SECOND);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

      OperationPointPayload.STREAM_CODEC.encode(buffer, payload);
      OperationPointPayload decoded = OperationPointPayload.STREAM_CODEC.decode(buffer);

      assertEquals(OperationPointPayload.Role.SECOND, decoded.role());
      assertEquals(1, buffer.writerIndex());
   }

   @Test
   void removedPointIndexSurvivesCodecRoundTrip() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationRemovePointPayload.STREAM_CODEC.encode(buffer, new OperationRemovePointPayload(37));

      assertEquals(37, OperationRemovePointPayload.STREAM_CODEC.decode(buffer).index());
   }
}
