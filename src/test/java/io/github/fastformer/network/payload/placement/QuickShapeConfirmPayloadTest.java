package io.github.fastformer.network.payload.placement;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class QuickShapeConfirmPayloadTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void codecKeepsRequestAndSnapshotIdentity() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         var payload = new QuickShapeConfirmPayload(42, 8, SCOPE);
         QuickShapeConfirmPayload.STREAM_CODEC.encode(buffer, payload);
         assertEquals(payload, QuickShapeConfirmPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally {
         buffer.release();
      }
   }

   @Test
   void rejectsNewerDraftAndChangedEnvironment() {
      var payload = new QuickShapeConfirmPayload(42, 8, SCOPE);
      assertTrue(payload.matches(8, SCOPE));
      assertFalse(payload.matches(9, SCOPE));
      assertFalse(payload.matches(8, new OperationCallbackScope(SCOPE.playerId(), SCOPE.dimension(), UUID.randomUUID())));
      assertFalse(payload.matches(8, new OperationCallbackScope(SCOPE.playerId(), ResourceLocation.withDefaultNamespace("the_nether"), SCOPE.sessionId())));
      assertFalse(payload.matches(8, new OperationCallbackScope(UUID.randomUUID(), SCOPE.dimension(), SCOPE.sessionId())));
   }

   @Test
   void rejectsUnidentifiedConfirmation() {
      assertThrows(IllegalArgumentException.class, () -> new QuickShapeConfirmPayload(0, 8, SCOPE));
      assertThrows(IllegalArgumentException.class, () -> new QuickShapeConfirmPayload(42, 0, SCOPE));
      assertThrows(IllegalArgumentException.class, () -> new QuickShapeConfirmPayload(42, 8, OperationCallbackScope.unscoped()));
   }
}
