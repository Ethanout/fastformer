package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationTransformPayloadTest {
   @Test
   void validatesOperationAxisAndStepBounds() {
      assertTrue(new OperationTransformPayload(0, 0, -1, 128, false).valid());
      assertTrue(new OperationTransformPayload(2, 2, 0, -128, true).valid());
      assertFalse(new OperationTransformPayload(-1, 0, 1, 0, false).valid());
      assertFalse(new OperationTransformPayload(3, 0, 1, 0, false).valid());
      assertFalse(new OperationTransformPayload(0, 3, 1, 0, false).valid());
      assertFalse(new OperationTransformPayload(0, 0, 2, 0, false).valid());
      assertFalse(new OperationTransformPayload(0, 0, 1, 129, false).valid());
   }

   @Test
   void directionTotalStepsAndFinishSurviveCodecRoundTrip() {
      OperationTransformPayload payload = new OperationTransformPayload(1, 2, -1, 37, true);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

      OperationTransformPayload.STREAM_CODEC.encode(buffer, payload);

      assertTrue(payload.equals(OperationTransformPayload.STREAM_CODEC.decode(buffer)));
   }
}
