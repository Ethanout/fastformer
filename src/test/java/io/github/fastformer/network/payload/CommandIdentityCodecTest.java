package io.github.fastformer.network.payload;

import io.github.fastformer.protocol.c2s.geometry.ClosePathPayload;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.c2s.operation.OperationTransformPayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.s2c.settings.FallingStatePayload;
import io.github.fastformer.protocol.s2c.settings.InteractionUpdatesPayload;
import io.github.fastformer.protocol.c2s.settings.SettingPayload;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandIdentityCodecTest {
   @Test void idleEntryAndTinkerRoundTripTheCapturedRayAndShape() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      var position = new net.minecraft.core.BlockPos(2, 64, 3);
      var eye = new Vec3(2.5, 66, 3.5);
      var view = new Vec3(0, -1, 0);
      var hit = new net.minecraft.world.phys.BlockHitResult(new Vec3(2.5, 65, 3.5), net.minecraft.core.Direction.UP, position, false);
      var rules = new io.github.fastformer.common.raycast.InteractionRaycastRules(true, true);
      var capture = new io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload(123, hit, eye, view, rules);
      var snapshot = new io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload(123,
         net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"),
         new io.github.fastformer.common.raycast.InteractionShapeSnapshot(position,
            java.util.List.of(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 0.5, 1))));
      var tinker = new io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload(123, hit, eye, view);
      var selection = new io.github.fastformer.protocol.c2s.placement.IdleSelectionEntryPayload(456, 123, eye, view);
      var result = new io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload(456,
         net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"), position, true);
      try {
         io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload.STREAM_CODEC.encode(buffer, capture);
         io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload.STREAM_CODEC.encode(buffer, snapshot);
         io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload.STREAM_CODEC.encode(buffer, tinker);
         io.github.fastformer.protocol.c2s.placement.IdleSelectionEntryPayload.STREAM_CODEC.encode(buffer, selection);
         io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload.STREAM_CODEC.encode(buffer, result);
         var decodedCapture = io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload.STREAM_CODEC.decode(buffer);
         assertEquals(capture.token(), decodedCapture.token());
         assertEquals(eye, decodedCapture.eye());
         assertEquals(view, decodedCapture.view());
         assertEquals(rules, decodedCapture.rules());
         assertEquals(position, decodedCapture.hit().getBlockPos());
         assertEquals(hit.getLocation(), decodedCapture.hit().getLocation());
         assertEquals(snapshot, io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload.STREAM_CODEC.decode(buffer));
         var decodedTinker = io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload.STREAM_CODEC.decode(buffer);
         assertEquals(tinker.token(), decodedTinker.token());
         assertEquals(eye, decodedTinker.eye());
         assertEquals(hit.getLocation(), decodedTinker.hit().getLocation());
         assertEquals(selection, io.github.fastformer.protocol.c2s.placement.IdleSelectionEntryPayload.STREAM_CODEC.decode(buffer));
         assertEquals(result, io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload.STREAM_CODEC.decode(buffer));
         assertEquals(0, buffer.readableBytes());
      } finally { buffer.release(); }
      assertThrows(IllegalArgumentException.class, () -> new io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload(123, hit, eye, new Vec3(0, 0, 0)));
   }
   @Test void historyEventRetainsOperationDimensionAndCompletion() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         for (var kind : io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.values()) {
            var event = new io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload(java.util.UUID.randomUUID(),
               net.minecraft.resources.ResourceLocation.parse("minecraft:the_nether"), kind, false);
            io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.STREAM_CODEC.encode(buffer, event);
            assertEquals(event, io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.STREAM_CODEC.decode(buffer));
         }
         assertEquals(0, buffer.readableBytes());
      } finally { buffer.release(); }
   }
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
   @Test void fallingRequestAndAuthorityResponseRoundTrip() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         var request = new SettingPayload(SettingsKeys.FALLING_DISABLED.set(true));
         var response = new FallingStatePayload(false, false);
         var interaction = new InteractionUpdatesPayload(true, false, true, false);
         SettingPayload.STREAM_CODEC.encode(buffer, request);
         FallingStatePayload.STREAM_CODEC.encode(buffer, response);
         InteractionUpdatesPayload.STREAM_CODEC.encode(buffer, interaction);
         assertEquals(request, SettingPayload.STREAM_CODEC.decode(buffer));
         assertEquals(response, FallingStatePayload.STREAM_CODEC.decode(buffer));
         assertEquals(interaction, InteractionUpdatesPayload.STREAM_CODEC.decode(buffer));
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
