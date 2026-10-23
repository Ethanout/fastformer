package io.github.fastformer.network.payload;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.network.payload.placement.SmartFillCommandPayload;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SmartFillCommandCodecTest {
   @Test void retainsRequestDimensionAndCapturedRay() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         for (var action : SmartFillCommandPayload.Action.values()) {
            var command = new SmartFillCommandPayload(UUID.randomUUID(), ResourceLocation.parse("minecraft:overworld"),
               action, new Vec3(0.5, 70, -3), new Vec3(0, -1, 0));
            SmartFillCommandPayload.STREAM_CODEC.encode(buffer, command);
            assertEquals(command, SmartFillCommandPayload.STREAM_CODEC.decode(buffer));
         }
         assertEquals(0, buffer.readableBytes());
      } finally { buffer.release(); }
   }
   @Test void rejectsInvalidRaysBeforeDispatch() {
      for (var view : java.util.List.of(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), new Vec3(2, 0, 0))) {
         assertThrows(IllegalArgumentException.class, () -> new SmartFillCommandPayload(UUID.randomUUID(),
            ResourceLocation.parse("minecraft:overworld"), SmartFillCommandPayload.Action.DISCOVER, Vec3.ZERO, view));
      }
      assertThrows(IllegalArgumentException.class, () -> new SmartFillCommandPayload(UUID.randomUUID(),
         ResourceLocation.parse("minecraft:overworld"), SmartFillCommandPayload.Action.DISCOVER,
         new Vec3(Double.POSITIVE_INFINITY, 0, 0), new Vec3(0, 0, 1)));
   }
}
