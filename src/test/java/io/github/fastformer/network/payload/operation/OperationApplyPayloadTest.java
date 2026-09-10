package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.network.payload.placement.PlacementActionAckPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationApplyPayloadTest {
   @Test
   void copyAndMoveKeepTheirRequestIdentityThroughTheWire() {
      for (boolean copy : new boolean[] {false, true}) {
         var buffer = new FriendlyByteBuf(Unpooled.buffer());
         try {
            var request = new OperationApplyPayload(copy, Long.MAX_VALUE);
            OperationApplyPayload.STREAM_CODEC.encode(buffer, request);
            var decoded = OperationApplyPayload.STREAM_CODEC.decode(buffer);
            assertEquals(request, decoded);
            var acknowledgement = new PlacementActionAckPayload(decoded.requestId());
            PlacementActionAckPayload.STREAM_CODEC.encode(buffer, acknowledgement);
            assertEquals(acknowledgement, PlacementActionAckPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
         } finally {
            buffer.release();
         }
      }
   }
}
