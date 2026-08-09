package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class OperationWorkspaceApplyPayloadTest {
   @Test
   void chunkMetadataAndBytesSurviveCodecRoundTrip() {
      UUID transfer = UUID.randomUUID();
      OperationWorkspaceApplyPayload payload = new OperationWorkspaceApplyPayload(
         transfer, 2, 4, new byte[] {1, 3, 5, 7}
      );
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

      OperationWorkspaceApplyPayload.STREAM_CODEC.encode(buffer, payload);
      OperationWorkspaceApplyPayload decoded = OperationWorkspaceApplyPayload.STREAM_CODEC.decode(buffer);

      assertEquals(transfer, decoded.transferId());
      assertEquals(2, decoded.chunkIndex());
      assertEquals(4, decoded.chunkCount());
      assertArrayEquals(payload.data(), decoded.data());
   }

   @Test
   void rejectsOutOfRangeChunkMetadata() {
      assertThrows(IllegalArgumentException.class, () -> new OperationWorkspaceApplyPayload(
         UUID.randomUUID(), 4, 4, new byte[] {1}
      ));
   }
}
