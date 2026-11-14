package io.github.fastformer.protocol.c2s.placement;

import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleGesturePayloadTest {
   private static final Vec3 EYE = new Vec3(0.5, 0.5, -20);
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final BlockHitResult HIT = new BlockHitResult(new Vec3(0.5, 0.5, 0), Direction.NORTH, BlockPos.ZERO, false);
   private static final DoubleClickPolicy POLICY = new DoubleClickPolicy(
      List.of(new DoubleClickPolicy.Band(0, 10, 125, true, 0, 0),
         new DoubleClickPolicy.Band(10, Double.MAX_VALUE, 450, false, 1, 0.25)),
      List.of(new DoubleClickPolicy.Acceptance(1, -1), new DoubleClickPolicy.Acceptance(-1, 0.125)));

   @Test void ordinaryFirstPressPreservesFrozenDoublePolicyAndRayRules() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         var input = new IdleInteractionCapturePayload(41, HIT, EYE, VIEW, InteractionRaycastRules.VANILLA_OUTLINE, POLICY);
         IdleInteractionCapturePayload.STREAM_CODEC.encode(buffer, input);
         var output = IdleInteractionCapturePayload.STREAM_CODEC.decode(buffer);
         assertEquals(41, output.token());
         assertEquals(EYE, output.eye());
         assertEquals(VIEW, output.view());
         assertEquals(input.rules(), output.rules());
         assertEquals(POLICY, output.doubleClick());
         assertEquals(HIT.getBlockPos(), output.hit().getBlockPos());
         assertFalse(buffer.isReadable());
      } finally { buffer.release(); }
   }

   @Test void tinkerFirstPressUsesTheSameConfiguredPolicyAsTheRestoreEntry() {
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         TinkerInteractionPayload.STREAM_CODEC.encode(buffer, new TinkerInteractionPayload(42, HIT, EYE, VIEW, POLICY));
         var output = TinkerInteractionPayload.STREAM_CODEC.decode(buffer);
         assertEquals(42, output.token());
         assertEquals(POLICY, output.doubleClick());
         assertFalse(buffer.isReadable());
      } finally { buffer.release(); }
   }
}
