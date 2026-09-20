package io.github.fastformer.network.payload.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ActivityStatePayloadTest {
   @Test
   void revisionSurvivesCodecRoundTrip() {
      OperationCallbackScope scope = new OperationCallbackScope(
         UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
      );
      ActivityStatePayload payload = new ActivityStatePayload(42L, FastPlaceActivity.GEOMETRY_SESSION, scope);
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

      ActivityStatePayload.STREAM_CODEC.encode(buffer, payload);
      ActivityStatePayload decoded = ActivityStatePayload.STREAM_CODEC.decode(buffer);

      assertEquals(payload, decoded);
      assertEquals(scope, decoded.callbackScope());
   }
}
