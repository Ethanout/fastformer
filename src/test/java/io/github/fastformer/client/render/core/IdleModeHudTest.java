package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class IdleModeHudTest {
   @Test void emptyHandShowsRememberedSelectionTool() {
      var previous = SelectionToolPreference.get();
      try {
         for (var mode : new OperationSelectionMode[] {OperationSelectionMode.CUBOID, OperationSelectionMode.SMART}) {
            SelectionToolPreference.set(mode);
            var status = FastPlaceClientPreviewCore.idleBottomStatus(true, false);
            assertEquals("fastformer.hud.selection_label", key(status));
            var highlighted = status.getSiblings().stream().filter(component -> component.getStyle().getColor() != null
               && component.getStyle().getColor().getValue() == GeometryPalette.accent().rgb()).toList();
            assertEquals(1, highlighted.size());
            assertEquals(mode.translationKey(), key(highlighted.getFirst()));
         }
      } finally {
         SelectionToolPreference.set(previous);
      }
   }

   @Test void heldBlockShowsQuickShapeLineStage() {
      var status = FastPlaceClientPreviewCore.idleBottomStatus(false, true);
      assertEquals("fastformer.stage.line", key(status));
      var modes = status.getSiblings().stream()
         .filter(component -> component.getContents() instanceof TranslatableContents).toList();
      assertEquals(java.util.List.of("fastformer.mode.line.axis", "fastformer.mode.line.free_scroll", "fastformer.mode.line.raycast"),
         modes.stream().map(IdleModeHudTest::key).toList());
      assertEquals(1, modes.stream().filter(component -> component.getStyle().getColor() != null
         && component.getStyle().getColor().getValue() == GeometryPalette.accent().rgb()).count());
      assertNull(FastPlaceClientPreviewCore.idleBottomStatus(false, false));
   }

   private static String key(Component component) {
      return ((TranslatableContents) component.getContents()).getKey();
   }
}
