package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPreviewPlanTest {
   @Test
   void builderMaterializesDefaultStatusAndHintContent() {
      GeometryStageDisplay display = GeometryStageDisplay.modes(
         List.of(
            new GeometryStageDisplay.Mode(Component.literal("点加半径"), true),
            new GeometryStageDisplay.Mode(Component.literal("两点直径"), false)
         )
      );

      GeometryPreviewPlan plan = GeometryPreviewPlan.builder(List.of(), null)
         .stage(Component.literal("确定点"))
         .hud(Component.empty(), Component.literal("按右键确认"))
         .stageDisplay(display)
         .textBlock(GeometryTextBlock.hidden(
            GeometryTextBlock.STAGE_ID,
            GeometryTextBlock.Placement.BOTTOM_CENTER
         ))
         .build();

      assertEquals("点加半径 / 两点直径", plan.textBlock(GeometryTextBlock.STATUS_ID).content().getString());
      assertEquals("按右键确认", plan.textBlock(GeometryTextBlock.HINT_ID).content().getString());
      assertTrue(plan.textVisible(GeometryTextBlock.MODE_ID));
      assertTrue(plan.textVisible("missing"));
   }

   @Test
   void explicitTextBlockContentAndPlacementArePreserved() {
      GeometryTextBlock custom = new GeometryTextBlock(
         "selection_hint",
         GeometryTextBlock.Placement.TOP_RIGHT,
         Component.literal("选择点"),
         true
      );

      GeometryPreviewPlan plan = GeometryPreviewPlan.builder(List.of(), null)
         .textBlock(custom)
         .build();

      assertEquals(custom, plan.textBlock("selection_hint"));
      assertEquals(GeometryTextBlock.Placement.TOP_RIGHT, plan.textBlock("selection_hint").placement());

      AxisGizmo gizmo = AxisGizmo.world(new Vec3(1.5, 2.5, 3.5), 2.0, 0.25);
      GeometryPreviewPlan withGizmo = plan.withGizmo(gizmo);
      assertEquals(gizmo, withGizmo.gizmo());
      assertEquals(plan.textBlocks(), withGizmo.textBlocks());
      assertEquals(plan.interactionTargets(), withGizmo.interactionTargets());
   }

   @Test
   void defaultControlPointsDoNotClaimCloseableInteraction() {
      GeometryPreviewPlan plan = GeometryPreviewPlan.builder(
         List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0)),
         new BlockPos(0, 0, 0)
      ).build();

      assertFalse(plan.controlPoints().getFirst().hovered());
      assertFalse(plan.controlPoints().getFirst().feedback().changesOnHover());
   }
}
