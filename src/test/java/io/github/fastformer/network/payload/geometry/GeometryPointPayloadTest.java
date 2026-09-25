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

class GeometryPointPayloadTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void codecKeepsCapturedPreviewAndRay() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         GeometryPointPayload payload = new GeometryPointPayload(1L, 7L, SCOPE, new Vec3(1.25, 64.0, -3.5), new Vec3(0.0, 0.0, 1.0));
         GeometryPointPayload.STREAM_CODEC.encode(buffer, payload);

         assertEquals(payload, GeometryPointPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally {
         buffer.release();
      }
   }

   @Test
   void rejectsMissingOrUnsafeSnapshots() {
      assertThrows(IllegalArgumentException.class, () -> new GeometryPointPayload(0L, 1L, SCOPE, Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class, () -> new GeometryPointPayload(1L, 0L, SCOPE, Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class, () -> new GeometryPointPayload(1L, 1L, OperationCallbackScope.unscoped(), Vec3.ZERO, new Vec3(0, 0, 1)));
      assertThrows(IllegalArgumentException.class, () -> new GeometryPointPayload(1L, 1L, SCOPE, Vec3.ZERO, new Vec3(0, 0, 2)));
      assertThrows(IllegalArgumentException.class, () -> new GeometryPointPayload(1L, 1L, SCOPE,
         new Vec3(Double.NaN, 0, 0), new Vec3(0, 0, 1)));
   }
}
