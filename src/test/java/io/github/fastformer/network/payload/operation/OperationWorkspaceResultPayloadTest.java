package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OperationWorkspaceResultPayloadTest {
   @Test
   void resultAndFailedPartIdsSurviveCodecRoundTrip() {
      UUID transferId = UUID.randomUUID();
      OperationWorkspaceResultPayload payload = new OperationWorkspaceResultPayload(
         transferId, false, List.of(2, 7), List.of(new BlockPos(1, 2, 3))
      ).withCallbackScope(new OperationCallbackScope(
         UUID.fromString("00000000-0000-0000-0000-000000000022"),
         ResourceLocation.withDefaultNamespace("the_end"),
         UUID.fromString("00000000-0000-0000-0000-000000000023")
      ));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationWorkspaceResultPayload.STREAM_CODEC.encode(buffer, payload);

      OperationWorkspaceResultPayload decoded = OperationWorkspaceResultPayload.STREAM_CODEC.decode(buffer);

      assertEquals(transferId, decoded.transferId());
      assertEquals(false, decoded.accepted());
      assertEquals(true, decoded.retryable());
      assertEquals(payload.callbackScope(), decoded.callbackScope());
      assertEquals(List.of(2, 7), decoded.failedPartIds());
      assertEquals(List.of(new BlockPos(1, 2, 3)), decoded.failedTargetPositions());
   }

   @Test
   void resultAcceptsPartIdsBeyondTheFormerTenPartLimit() {
      UUID transferId = UUID.randomUUID();
      assertDoesNotThrow(() -> new OperationWorkspaceResultPayload(transferId, false, List.of(11, 42, 1000)));
   }

   @Test
   void nonRetryableResultSurvivesCodecRoundTrip() {
      UUID transferId = UUID.randomUUID();
      OperationWorkspaceResultPayload payload = new OperationWorkspaceResultPayload(
         transferId, false, false, List.of(), List.of()
      );
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationWorkspaceResultPayload.STREAM_CODEC.encode(buffer, payload);

      assertEquals(false, OperationWorkspaceResultPayload.STREAM_CODEC.decode(buffer).retryable());
   }
}
