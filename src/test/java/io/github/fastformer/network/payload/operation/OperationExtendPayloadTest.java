package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

class OperationExtendPayloadTest {
   @Test
   void acceptsFaceSelectionAndPointAxes() {
      assertFalse(OperationExtendPayload.validAxis(-1));
      for (int axis = 0; axis <= 8; axis++) {
         assertTrue(OperationExtendPayload.validAxis(axis));
      }
      assertFalse(OperationExtendPayload.validAxis(9));
   }

   @Test
   void codecKeepsDragIdentity() {
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var payload = new OperationExtendPayload(9L, 4L, scope, 2, true, 3, true);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationExtendPayload.STREAM_CODEC.encode(buffer, payload);
      assertEquals(payload, OperationExtendPayload.STREAM_CODEC.decode(buffer));
   }
}
