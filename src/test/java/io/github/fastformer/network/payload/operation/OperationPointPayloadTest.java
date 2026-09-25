package io.github.fastformer.network.payload.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

class OperationPointPayloadTest {
   @Test
   void codecCarriesOnlyTheRaycastIntent() {
      OperationPointPayload payload = new OperationPointPayload(OperationPointPayload.Role.SECOND);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

      OperationPointPayload.STREAM_CODEC.encode(buffer, payload);
      OperationPointPayload decoded = OperationPointPayload.STREAM_CODEC.decode(buffer);

      assertEquals(OperationPointPayload.Role.SECOND, decoded.role());
      assertEquals(1, buffer.writerIndex());
   }

   @Test
   void selectionCommandCodecKeepsCapturedIdentityAndView() {
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var payload = new OperationSelectPointPayload(7L, 3L, scope, 2, new Vec3(1, 2, 3), new Vec3(0, 0, 1));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationSelectPointPayload.STREAM_CODEC.encode(buffer, payload);
      var decoded = OperationSelectPointPayload.STREAM_CODEC.decode(buffer);
      assertEquals(payload, decoded);
   }

   @Test
   void edgeCommandCodecKeepsCapturedView() {
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var payload = new OperationInsertPointPayload(8L, 4L, scope, new Vec3(4, 5, 6), new Vec3(0, 1, 0));
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      OperationInsertPointPayload.STREAM_CODEC.encode(buffer, payload);
      assertEquals(payload, OperationInsertPointPayload.STREAM_CODEC.decode(buffer));
   }

}
