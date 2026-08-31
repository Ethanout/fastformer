package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationWorkspaceResultPayloadTest {
   @Test
   void resultAndFailedPartIdsSurviveCodecRoundTrip() {
      UUID transferId = UUID.randomUUID();
      OperationWorkspaceResultPayload payload = new OperationWorkspaceResultPayload(transferId, false, List.of(2, 7));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationWorkspaceResultPayload.STREAM_CODEC.encode(buffer, payload);

      OperationWorkspaceResultPayload decoded = OperationWorkspaceResultPayload.STREAM_CODEC.decode(buffer);

      assertEquals(transferId, decoded.transferId());
      assertEquals(false, decoded.accepted());
      assertEquals(List.of(2, 7), decoded.failedPartIds());
   }

   @Test
   void resultAcceptsPartIdsBeyondTheFormerTenPartLimit() {
      UUID transferId = UUID.randomUUID();
      assertDoesNotThrow(() -> new OperationWorkspaceResultPayload(transferId, false, List.of(11, 42, 1000)));
   }
}
