package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationStageMode;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPreviewPayloadTest {
   @Test
   void prismStageAndSelectedPointSurviveCodecRoundTrip() {
      OperationPreviewPayload payload = OperationPreviewPayload.active(
         37L,
         true,
         true,
         List.of(
            new BlockPos(0, 0, 0), new BlockPos(4, 0, 0),
            new BlockPos(3, 0, 3), new BlockPos(0, 5, 0)
         ),
         BlockPos.ZERO,
         new BlockPos(0, 0, 2),
         OperationSelectionMode.PRISM,
         3,
         1,
         0,
         OperationMode.MOVE,
         OperationStageMode.TRANSFORM,
         BlockPos.ZERO,
         new BlockPos(-2, 0, 0),
         new BlockPos(3, 4, 0),
         new Vec3(0.1, 0.2, 0.3),
         true,
         false,
         false
      );

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationPreviewPayload.STREAM_CODEC.encode(buffer, payload);
      OperationPreviewPayload decoded = OperationPreviewPayload.STREAM_CODEC.decode(buffer);

      assertEquals(payload.points(), decoded.points());
      assertEquals(payload.selectionMin(), decoded.selectionMin());
      assertEquals(payload.selectionMax(), decoded.selectionMax());
      assertEquals(3, decoded.operationPrismBasePointCount());
      assertEquals(1, decoded.operationSelectedPointIndex());
      assertEquals(new BlockPos(0, 0, 2), decoded.operationMaxOffset());
      assertEquals(new BlockPos(-2, 0, 0), decoded.operationStackMin());
      assertEquals(new BlockPos(3, 4, 0), decoded.operationStackMax());
      assertEquals(true, decoded.operationAdjustmentStarted());
      assertEquals(OperationStageMode.TRANSFORM, decoded.operationStageMode());
      assertEquals(new Vec3(0.1, 0.2, 0.3), decoded.operationRotation());
      assertEquals(37L, decoded.operationRevision());
   }

   @Test
   void cuboidBoundsAreIndependentFromPointIdentity() {
      OperationPreviewPayload payload = OperationPreviewPayload.active(
         3L, true, true, List.of(new BlockPos(10, 10, 10), new BlockPos(12, 12, 12)),
         new BlockPos(2, 3, 4), new BlockPos(20, 21, 22), BlockPos.ZERO, BlockPos.ZERO,
         OperationSelectionMode.CUBOID, 0, -1, 0, OperationMode.MOVE, OperationStageMode.TRANSFORM,
         BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, Vec3.ZERO, false, false, false
      );
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationPreviewPayload.STREAM_CODEC.encode(buffer, payload);
      OperationPreviewPayload decoded = OperationPreviewPayload.STREAM_CODEC.decode(buffer);
      assertEquals(new BlockPos(2, 3, 4), decoded.selectionMin());
      assertEquals(new BlockPos(20, 21, 22), decoded.selectionMax());
      assertEquals(payload.points(), decoded.points());
   }
}
