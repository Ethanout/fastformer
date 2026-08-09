package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationWorkspaceResultPayloadTest {
   @Test
   void resultAndFailedPartIdsSurviveCodecRoundTrip() {
      OperationWorkspaceResultPayload payload = new OperationWorkspaceResultPayload(false, List.of(2, 7));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationWorkspaceResultPayload.STREAM_CODEC.encode(buffer, payload);

      OperationWorkspaceResultPayload decoded = OperationWorkspaceResultPayload.STREAM_CODEC.decode(buffer);

      assertEquals(false, decoded.accepted());
      assertEquals(List.of(2, 7), decoded.failedPartIds());
   }
}
