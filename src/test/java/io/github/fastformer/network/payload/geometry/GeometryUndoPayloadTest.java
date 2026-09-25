package io.github.fastformer.network.payload.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryUndoPayloadTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );
   private static final UUID DRAFT = UUID.randomUUID();

   @Test
   void codecKeepsCapturedDraftAndRay() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         GeometryUndoPayload payload = new GeometryUndoPayload(
            1L, 7L, SCOPE, DRAFT, new Vec3(1.25, 64.0, -3.5), new Vec3(0.0, 0.0, 1.0)
         );
         GeometryUndoPayload.STREAM_CODEC.encode(buffer, payload);

         assertEquals(payload, GeometryUndoPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally {
         buffer.release();
      }
   }

   @Test
   void rejectsMissingOrUnsafeSnapshots() {
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryUndoPayload(0L, 1L, SCOPE, DRAFT, Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryUndoPayload(1L, 0L, SCOPE, DRAFT, Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryUndoPayload(1L, 1L, OperationCallbackScope.unscoped(), DRAFT, Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryUndoPayload(1L, 1L, SCOPE, new UUID(0L, 0L), Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class,
         () -> new GeometryUndoPayload(1L, 1L, SCOPE, DRAFT, Vec3.ZERO, new Vec3(0, 0, 2)));
   }
}
