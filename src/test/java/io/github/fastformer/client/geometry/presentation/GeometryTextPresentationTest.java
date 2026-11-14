package io.github.fastformer.client.geometry.presentation;


import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.client.preview.style.BuiltinThemes;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.preview.GeometryStageDisplay;
import io.github.fastformer.common.geometry.preview.GeometryTextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class GeometryTextPresentationTest {
   @Test void generatedStageBarUsesCurrentThemeAndLeavesSharedPlanUnstyled() {
      var previous = GeometryPalette.theme();
      var display = new GeometryStageDisplay(true, List.of(
         new GeometryStageDisplay.Mode(Component.literal("selected"), true),
         new GeometryStageDisplay.Mode(Component.literal("other"), false)), Component.literal("value"));
      var plan = GeometryPreviewPlan.builder(List.of(), null).stage(Component.literal("stage"))
         .stageDisplay(display).hud(Component.empty(), Component.literal("hint")).build();
      var status = plan.textBlock(GeometryTextBlock.STATUS_ID);
      var presentation = new GeometryTextPresentation();
      assertEquals(GeometryTextBlock.ContentSource.STAGE_BAR, status.source());
      assertTrue(colors(status.content()).stream().allMatch(color -> color == -1));
      try {
         for (var theme : GeometryPalette.snapshot().definitions()) {
            GeometryPalette.selectTheme(theme.id());
            var presented = presentation.present(plan);
            var text = presented.textBlock(GeometryTextBlock.STATUS_ID).content();
            assertEquals("stage：selected / other value", text.getString());
            assertEquals(List.of(GeometryPalette.text().rgb(), ChatFormatting.DARK_GRAY.getColor(),
               GeometryPalette.accent().rgb(), ChatFormatting.DARK_GRAY.getColor(), GeometryPalette.muted().rgb(),
               ChatFormatting.DARK_GRAY.getColor(), GeometryPalette.text().rgb()), colors(text));
            assertSame(plan.textBlock(GeometryTextBlock.HINT_ID), presented.textBlock(GeometryTextBlock.HINT_ID));
         }
      } finally { GeometryPalette.selectTheme(previous); }
      assertTrue(colors(status.content()).stream().allMatch(color -> color == -1));
   }

   @Test void explicitStatusContentPlacementAndVisibilityArePreserved() {
      var custom = new GeometryTextBlock(GeometryTextBlock.STATUS_ID, GeometryTextBlock.Placement.TOP_RIGHT,
         Component.literal("custom").withStyle(Style.EMPTY.withColor(0x123456)), false);
      var plan = GeometryPreviewPlan.builder(List.of(), null).stage(Component.literal("ignored"))
         .textBlock(custom).build();
      var result = new GeometryTextPresentation().present(plan);
      assertSame(custom, result.textBlock(GeometryTextBlock.STATUS_ID));
      assertFalse(result.textBlock(GeometryTextBlock.STATUS_ID).visible());
   }

   @Test void layoutVisibilityControlsRemainIndependentAfterPresentation() {
      var plan = GeometryPreviewPlan.builder(List.of(), null).stage(Component.literal("hidden"))
         .hud(Component.literal("mode"), Component.empty())
         .textBlock(GeometryTextBlock.hidden(GeometryTextBlock.STAGE_ID, GeometryTextBlock.Placement.BOTTOM_CENTER))
         .build();
      var result = new GeometryTextPresentation().present(plan);
      assertEquals("mode", result.textBlock(GeometryTextBlock.STATUS_ID).content().getString());
      assertFalse(result.textVisible(GeometryTextBlock.STAGE_ID));
      assertTrue(result.textVisible(GeometryTextBlock.MODE_ID));
   }

   @Test void cacheUsesSourceIdentityAndClearDropsOwnedResult() {
      var presentation = new GeometryTextPresentation();
      var plan = GeometryPreviewPlan.builder(List.of(), null).stage(Component.literal("stage")).build();
      var first = presentation.present(plan);
      assertSame(first, presentation.present(plan));
      var other = plan.withGizmo(null);
      assertEquals(plan, other);
      assertNotSame(first, presentation.present(other));
      var beforeClear = presentation.present(plan);
      presentation.clear();
      var afterClear = presentation.present(plan);
      assertNotSame(beforeClear, afterClear);
      assertEquals(beforeClear, afterClear);
      assertSame(afterClear, presentation.present(plan));
   }

   @Test void themeRevisionInvalidatesCachedTextForTheSameRawPlan() {
      var previous = GeometryPalette.theme();
      var presentation = new GeometryTextPresentation();
      var plan = GeometryPreviewPlan.builder(List.of(), null).stage(Component.literal("stage")).build();
      try {
         GeometryPalette.selectTheme(BuiltinThemes.CLASSIC);
         var classic = presentation.present(plan);
         assertSame(classic, presentation.present(plan));
         GeometryPalette.selectTheme(BuiltinThemes.HUMANIST);
         var humanist = presentation.present(plan);
         assertNotSame(classic, humanist);
         assertEquals(List.of(GeometryPalette.text().rgb()), colors(humanist.textBlock(GeometryTextBlock.STATUS_ID).content()));
         assertSame(humanist, presentation.present(plan));
      } finally { GeometryPalette.selectTheme(previous); }
   }

   private static List<Integer> colors(Component text) {
      List<Integer> result = new ArrayList<>();
      text.visit((style, part) -> {
         if (!part.isEmpty()) result.add(style.getColor() == null ? -1 : style.getColor().getValue());
         return Optional.empty();
      }, Style.EMPTY);
      return result;
   }
}
