package io.github.fastformer.protocol.s2c.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OperationWorkspaceReceiptPayloadTest {
   @Test
   void dimensionAndEveryReportableOutcomeSurviveRoundTrip() {
      var entries = java.util.Arrays.stream(OperationSubmissionOutcome.values())
         .filter(OperationSubmissionOutcome::reportable)
         .map(outcome -> new OperationWorkspaceReceiptPayload.Entry(UUID.randomUUID(), outcome))
         .toList();
      roundTrip(new OperationWorkspaceReceiptPayload(ResourceLocation.withDefaultNamespace("the_end"), entries));
   }

   @Test
   void emptyAndMaximumSizeAnswersPreserveThePacketBoundary() {
      var dimension = ResourceLocation.withDefaultNamespace("overworld");
      roundTrip(new OperationWorkspaceReceiptPayload(dimension, List.of()));
      var entries = IntStream.range(0, OperationWorkspaceReceiptPayload.MAX_ENTRIES)
         .mapToObj(index -> new OperationWorkspaceReceiptPayload.Entry(new UUID(0, index), OperationSubmissionOutcome.APPLIED))
         .toList();
      roundTrip(new OperationWorkspaceReceiptPayload(dimension, entries));
   }

   private static void roundTrip(OperationWorkspaceReceiptPayload payload) {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         OperationWorkspaceReceiptPayload.STREAM_CODEC.encode(buffer, payload);
         buffer.writeInt(0x13572468);
         assertEquals(payload, OperationWorkspaceReceiptPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0x13572468, buffer.readInt());
         assertEquals(0, buffer.readableBytes());
      } finally {
         buffer.release();
      }
   }
}
