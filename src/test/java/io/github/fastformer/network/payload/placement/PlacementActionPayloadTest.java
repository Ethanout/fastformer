package io.github.fastformer.network.payload.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlacementActionPayloadTest {
   @Test
   void nullActionUsesTheSafeConfirmDefault() {
      assertEquals(PlacementActionPayload.Action.CONFIRM, new PlacementActionPayload(null).action());
   }
   @Test
   void codecPreservesSemanticAction() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      PlacementActionPayload.STREAM_CODEC.encode(
         buffer,
         new PlacementActionPayload(PlacementActionPayload.Action.QUICK_SHAPE, 42L)
      );

      PlacementActionPayload decoded = PlacementActionPayload.STREAM_CODEC.decode(buffer);
      assertEquals(PlacementActionPayload.Action.QUICK_SHAPE, decoded.action());
      assertEquals(42L, decoded.requestId());
   }

   @Test
   void codecRejectsUnknownProtocolVersion() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      buffer.writeVarInt(PlacementActionPayload.PROTOCOL_VERSION + 1);
      assertThrows(IllegalArgumentException.class, () -> PlacementActionPayload.STREAM_CODEC.decode(buffer));
   }

   @Test
   void payloadDoesNotContainShapeOrBlockData() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      PlacementActionPayload.STREAM_CODEC.encode(
         buffer,
         new PlacementActionPayload(PlacementActionPayload.Action.CONFIRM)
      );

      assertEquals(3, buffer.readableBytes());
   }

   @Test
   void requestIdMustBeNonNegative() {
      assertThrows(
         IllegalArgumentException.class,
         () -> new PlacementActionPayload(PlacementActionPayload.Action.CONFIRM, -1L)
      );
   }

   @Test
   void startPlacementPayloadPreservesCapturedTarget() {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var target = new StartPlacementPayload.Target(7L, scope, RaycastPlacement.EMBEDDED,
         new BlockHitResult(new Vec3(4.25, 5.5, 6.75), Direction.UP, new BlockPos(4, 5, 6), false),
         new Vec3(1, 2, 3), new Vec3(0, 0, 1));
      StartPlacementPayload.STREAM_CODEC.encode(
         buffer, new StartPlacementPayload(9L, target)
      );

      StartPlacementPayload decoded = StartPlacementPayload.STREAM_CODEC.decode(buffer);
      assertEquals(9L, decoded.requestId());
      assertEquals(target.revision(), decoded.target().revision());
      assertEquals(target.callbackScope(), decoded.target().callbackScope());
      assertEquals(target.placement(), decoded.target().placement());
      assertEquals(target.hit().getBlockPos(), decoded.target().hit().getBlockPos());
      assertEquals(target.hit().getLocation(), decoded.target().hit().getLocation());
      assertEquals(target.eye(), decoded.target().eye());
      assertEquals(target.view(), decoded.target().view());
   }

   @Test
   void startPlacementRejectsIncompleteOrUnsafeCapture() {
      assertThrows(IllegalArgumentException.class, () -> new StartPlacementPayload(0L, null));
      assertThrows(IllegalArgumentException.class, () -> new StartPlacementPayload.Target(0L,
         OperationCallbackScope.unscoped(), RaycastPlacement.SURFACE,
         new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false), Vec3.ZERO, new Vec3(0, 0, 1)));
   }
}
