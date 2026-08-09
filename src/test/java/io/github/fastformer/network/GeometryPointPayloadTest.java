package io.github.fastformer.network;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class GeometryPointPayloadTest {
   @Test
   void unitPayloadRoundTripsToSingleton() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      GeometryPointPayload.STREAM_CODEC.encode(buffer, GeometryPointPayload.INSTANCE);

      assertSame(GeometryPointPayload.INSTANCE, GeometryPointPayload.STREAM_CODEC.decode(buffer));
   }
}
