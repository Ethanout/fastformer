package io.github.fastformer.network.payload;

import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import io.github.fastformer.network.payload.operation.*;
import io.github.fastformer.network.payload.settings.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandIdentityCodecTest {
   @Test void closeAndTransformRoundTripTheirCapturedIdentity() {
      var scope = OperationCallbackScope.unscoped();
      var close = new ClosePathPayload(12, 35, scope, ClosePathPayload.Kind.GEOMETRY);
      var transform = new OperationTransformPayload(13, 11, 35, scope, 0, 1, 1, 4, true);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         ClosePathPayload.STREAM_CODEC.encode(buffer, close);
         OperationTransformPayload.STREAM_CODEC.encode(buffer, transform);
         assertEquals(close, ClosePathPayload.STREAM_CODEC.decode(buffer));
         assertEquals(transform, OperationTransformPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally { buffer.release(); }
   }
   @Test void freezeRequestAndAuthorityResponseRoundTrip() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         var request = new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_GLOBAL_FREEZE, true);
         var response = new FreezeStatePayload(false, false);
         SettingsActionPayload.STREAM_CODEC.encode(buffer, request);
         FreezeStatePayload.STREAM_CODEC.encode(buffer, response);
         assertEquals(request, SettingsActionPayload.STREAM_CODEC.decode(buffer));
         assertEquals(response, FreezeStatePayload.STREAM_CODEC.decode(buffer));
      } finally { buffer.release(); }
   }
   @Test void decoderRejectsMalformedRayBeforeDispatch() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         buffer.writeVarLong(1); buffer.writeVarLong(0);
         OperationCallbackScope.STREAM_CODEC.encode(buffer, OperationCallbackScope.unscoped());
         buffer.writeVec3(Vec3.ZERO); buffer.writeVec3(new Vec3(Double.NaN, 0, 1));
         assertThrows(IllegalArgumentException.class, () -> OperationInsertPointPayload.STREAM_CODEC.decode(buffer));
      } finally { buffer.release(); }
   }
}
