package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspacePointerPromptTest {
   private static final boolean LOCKED = true;
   private static final boolean UNLOCKED = false;

   @Test
   void aLockedWorkspaceAcceptsNoNewAction() {
      assertFalse(WorkspacePointerPrompt.acceptsNewAction(LOCKED));
      assertTrue(WorkspacePointerPrompt.acceptsNewAction(UNLOCKED));
   }

   @Test
   void aLockedFaceShowsNoDragOrClickPrompt() {
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(face(true), LOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(face(false), LOCKED).isEmpty());
   }

   @Test
   void aLockedWorkspaceShowsNoPromptForAnyOtherTarget() {
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(part(), LOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(gizmo(), LOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(create(), LOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(null, LOCKED).isEmpty());
   }

   @Test
   void anUnlockedAdjustableFaceKeepsBothDragCommands() {
      List<Component> lines = WorkspacePointerPrompt.crosshairActionLines(face(true), UNLOCKED);

      assertEquals(2, lines.size());
      assertEquals("fastformer.hud.selection.push", key(lines.get(0)));
      assertEquals("fastformer.hud.selection.pull", key(lines.get(1)));
   }

   @Test
   void anUnlockedFixedFaceKeepsTheSelectionCommand() {
      List<Component> lines = WorkspacePointerPrompt.crosshairActionLines(face(false), UNLOCKED);

      assertEquals(1, lines.size());
      assertEquals("fastformer.operation.selection_click_hint", key(lines.get(0)));
   }

   @Test
   void targetsWithoutACrosshairCommandStaySilentWhenUnlocked() {
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(part(), UNLOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(gizmo(), UNLOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(create(), UNLOCKED).isEmpty());
      assertTrue(WorkspacePointerPrompt.crosshairActionLines(null, UNLOCKED).isEmpty());
   }

   @Test
   void thePartLabelAndTheCrosshairShareTheSameLockAnswer() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(3, true, LOCKED, true, part()))
      );
      assertEquals(
         WorkspacePartLabelHint.DESELECT_KEY,
         key(WorkspacePartLabelHint.text(3, true, UNLOCKED, true, part()))
      );
   }

   private static OperationInteractionIntent.Part part() {
      return new OperationInteractionIntent.Part(3, 1.0);
   }

   private static OperationInteractionIntent.CreateSelection create() {
      return new OperationInteractionIntent.CreateSelection(BlockPos.ZERO);
   }

   private static OperationInteractionIntent.Face face(boolean adjustable) {
      return new OperationInteractionIntent.Face(
         3,
         new AABB(BlockPos.ZERO),
         new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 0.5, 0),
         adjustable
      );
   }

   private static OperationInteractionIntent.Gizmo gizmo() {
      AxisGizmo axisGizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      AxisGizmo.Handle handle = axisGizmo.handles().getFirst();
      return new OperationInteractionIntent.Gizmo(
         3, false, axisGizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1.0, 0.1)
      );
   }

   private static String key(Component text) {
      return ((TranslatableContents) text.getContents()).getKey();
   }
}
