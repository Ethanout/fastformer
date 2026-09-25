package io.github.fastformer.network.payload.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class GeometryGizmoDragPayloadTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void codecKeepsDragIdentityAndDelta() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         var payload = new GeometryGizmoDragPayload(4L, 7L, SCOPE, UUID.randomUUID(), 2, 1, -3, false);
         GeometryGizmoDragPayload.STREAM_CODEC.encode(buffer, payload);
         assertEquals(payload, GeometryGizmoDragPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally {
         buffer.release();
      }
   }

   @Test
   void rejectsMissingRequestOrDraftIdentity() {
      UUID draft = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryGizmoDragPayload(0L, 7L, SCOPE, draft, 0, 0, 1, false));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryGizmoDragPayload(1L, 0L, SCOPE, draft, 0, 0, 1, false));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryGizmoDragPayload(1L, 7L, OperationCallbackScope.unscoped(), draft, 0, 0, 1, false));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryGizmoDragPayload(1L, 7L, SCOPE, new UUID(0L, 0L), 0, 0, 1, false));
   }
}
