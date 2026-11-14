package io.github.fastformer.common.interaction.binding;

import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.quickshape.QuickShapeWorkflow;

import io.github.fastformer.common.quickshape.mode.FaceMode;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionProfileTest {
   private static SessionProfile hold() {
      return SessionProfile.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"session":"fastformer:quick_shape","max_stage":"line","locked_modes":{"line":"fastformer:axis"},
          "allow_mode_switch":false,"middle_confirm":"always",
          "entry":{"anchor":"embedded","requires":["creative","holding_placeable","aiming_block","within_reach"]}}
         """)).getOrThrow();
   }

   @Test void codecRoundTripPreservesImmutableConstraints() {
      var profile = hold();
      assertEquals(profile, SessionProfile.CODEC.parse(JsonOps.INSTANCE, SessionProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow()).getOrThrow());
      assertThrows(UnsupportedOperationException.class, () -> profile.lockedModes().clear());
      assertThrows(UnsupportedOperationException.class, () -> profile.entry().requires().clear());
   }

   @Test void secondPointSubmitsInsteadOfAdvancingToFace() {
      var draft = new QuickShapeDraft(ResourceLocation.parse("fastformer:hold_line"), hold());
      draft.addPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(1, 0, 0));
      var decision = QuickShapeWorkflow.onEvent(draft, FaceMode.COORDINATE_PLANE,
         new QuickShapeWorkflow.Event.ConfirmPoint(new BlockPos(4, 0, 0), Vec3.ZERO, new Vec3(1, 0, 0), false));
      assertEquals(QuickShapeWorkflow.Action.SUBMIT, decision.action());
      assertTrue(decision.addPoint());
      assertEquals(List.of(BlockPos.ZERO), draft.points());
   }

   @Test void overlappingSecondPointSubmitsWithoutDuplicate() {
      var draft = new QuickShapeDraft(ResourceLocation.parse("fastformer:hold_line"), hold());
      draft.addPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(1, 0, 0));
      var decision = QuickShapeWorkflow.onEvent(draft, FaceMode.POLYGON,
         new QuickShapeWorkflow.Event.ConfirmPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(1, 0, 0), false));
      assertEquals(QuickShapeWorkflow.Action.SUBMIT, decision.action());
      assertFalse(decision.addPoint());
   }
}
