package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class InteractionTooltipTest {
   @Test
   void framePolicyKeepsResolvedFaceCapabilities() {
      var frame = PartFrameInteraction.create(UUID.randomUUID(), 1, new AABB(0, 0, 0, 2, 2, 2));
      var editable = new OperationInteractionIntent.Face(1, frame.require(InteractionComponents.WORLD_BOUNDS), null, true);
      var locked = new OperationInteractionIntent.Face(1, editable.bounds(), null, false);
      var policy = frame.require(InteractionComponents.TOOLTIP);
      assertEquals(Component.translatable("fastformer.operation.face_drag_hint"), InteractionTooltip.summary(frame, editable).orElseThrow());
      assertEquals(List.of(Component.translatable("fastformer.hud.selection.push"),
         Component.translatable("fastformer.hud.selection.pull")), policy.lines(editable));
      assertEquals(List.of(Component.translatable("fastformer.operation.selection_click_hint")), policy.lines(locked));
      assertEquals(editable.requireHoverText(), policy.summary(editable).orElseThrow());
      assertEquals(locked.requireHoverTextLines(), policy.lines(locked));
   }

   @Test
   void unrelatedTargetsAndObjectsWithoutPolicyHaveNoTooltip() {
      UUID owner = UUID.randomUUID();
      var label = PartLabelInteraction.create(owner, 1, 1, new AABB(0, 0, 0, 2, 2, 2));
      var face = new OperationInteractionIntent.Face(1, new AABB(0, 0, 0, 2, 2, 2), null, true);
      assertTrue(InteractionTooltip.summary(label, face).isEmpty());
      var target = new OperationInteractionIntent.Part(1, 2);
      assertEquals(target.requireHoverText(), InteractionTooltip.summary(label, target).orElseThrow());
      var empty = InteractionObject.builder(new InteractionObject.Id(owner, "empty", 1)).build();
      assertTrue(InteractionTooltip.summary(empty, target).isEmpty());
   }

   @Test
   void gizmoPolicyUsesTheHitHandleTextWithoutChangingItsOperation() {
      var gizmo = AxisGizmo.inFrame(TransformFrame.world(Vec3.ZERO), 1, 0.1, AxisGizmo.Operation.MOVE);
      var handle = gizmo.handles().getFirst();
      var target = new OperationInteractionIntent.Gizmo(1, false, gizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1, 0));
      assertEquals(handle.hoverText(), InteractionTooltip.GIZMO.summary(target).orElseThrow());
      assertEquals(target.requireHoverText(), InteractionTooltip.GIZMO.summary(target).orElseThrow());
      assertSame(gizmo, target.gizmo());
      assertEquals(AxisGizmo.Operation.MOVE, target.hit().handle().operation());
   }
}
