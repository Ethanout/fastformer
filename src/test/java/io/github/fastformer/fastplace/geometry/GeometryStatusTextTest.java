package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GeometryStatusTextTest {
   @Test
   void stageBarShowsEveryModeWithSelectedModeHighlighted() {
      GeometryStageDisplay display = GeometryStageDisplay.modes(
         List.of(
            new GeometryStageDisplay.Mode(Component.literal("点加半径"), true),
            new GeometryStageDisplay.Mode(Component.literal("两点直径"), false),
            new GeometryStageDisplay.Mode(Component.literal("三点定面"), false)
         )
      );

      String text = GeometryStatusText.composeStageBar(
         Component.literal("确定点"), display, Component.empty(), true, true, true
      ).getString();

      assertEquals("确定点：点加半径 / 两点直径 / 三点定面", text);
   }

   @Test
   void stageBarCanHideModeWithoutHidingTheHintSlot() {
      GeometryStageDisplay display = GeometryStageDisplay.modes(
         List.of(new GeometryStageDisplay.Mode(Component.literal("点加半径"), true))
      );

      assertEquals(
         "确定点",
         GeometryStatusText.composeStageBar(Component.literal("确定点"), display, Component.empty(), true, false, false).getString()
      );
   }
}
