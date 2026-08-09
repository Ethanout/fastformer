package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GizmoTextTemplateTest {
   @Test
   void rendersControlledContextVariables() {
      GizmoTextContext context = new GizmoTextContext("X", "位移", "0", "+3", "3", "POSITIVE");

      assertEquals("X 位移", new GizmoTextTemplate("${axis} ${operation}").render(context));
      assertEquals("X 0+3", new GizmoTextComponent(
         new GizmoTextTemplate("${axis} ${operation}"),
         new GizmoTextTemplate("${axis} ${base}${delta}"),
         true,
         true
      ).render(context, true));
   }

   @Test
   void keepsUnknownVariablesAndSupportsIndependentVisibility() {
      GizmoTextComponent component = GizmoTextComponent.pointLevel()
         .withHoverVisible(false);
      GizmoTextContext context = new GizmoTextContext("X", "位移", "0", "+3", "3", "POSITIVE");

      assertEquals("", component.render(context, false));
      assertEquals("X 0+3", component.render(context, true));
      assertEquals("${missing}", new GizmoTextTemplate("${missing}").render(context));
   }

   @Test
   void activeUsesHoverColorAndTextBlocksDoNotShareVisibility() {
      HoverFeedback feedback = new HoverFeedback(0x112233, 0xAABBCC, 0x010203);
      assertEquals(0x112233, feedback.color(HoverFeedback.State.NORMAL));
      assertEquals(0xAABBCC, feedback.color(HoverFeedback.State.HOVER));
      assertEquals(0xAABBCC, feedback.color(HoverFeedback.State.ACTIVE));

      GeometryTextBlock visible = GeometryTextBlock.bottomCenter("stage", Component.literal("stage"));
      GeometryTextBlock hidden = visible.withVisible(false);
      assertSame(visible.content(), hidden.content());
      assertEquals("stage", visible.id());
      assertEquals(false, hidden.visible());

      GeometryPreviewPlan plan = GeometryPreviewPlan.builder(java.util.List.of(), null)
         .textBlock(hidden)
         .build();
      assertEquals(false, plan.textBlocks().stream().filter(block -> block.id().equals("stage")).findFirst().orElseThrow().visible());
      assertEquals(true, plan.textBlocks().stream().filter(block -> block.id().equals("mode")).findFirst().orElseThrow().visible());
      assertEquals(true, plan.textBlocks().stream().filter(block -> block.id().equals("hint")).findFirst().orElseThrow().visible());
   }
}
