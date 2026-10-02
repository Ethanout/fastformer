package io.github.fastformer.client.render.hud;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BottomHudLayoutTest {
   @Test void hintUsesVanillaItemNamePositionInCreativeAndSurvival() {
      assertEquals(195, layout(false, false, false).nextLine());
      assertEquals(181, layout(true, false, false).nextLine());
      assertEquals(150, new BottomHudLayout(240, 90, 49, true, false, false).nextLine());
   }

   @Test void itemNameTemporarilyReplacesBottomHintWithoutMovingModeLine() {
      var name = layout(false, true, false);
      var hint = layout(false, false, false);
      int nameRow = name.nextLine(), hintRow = hint.nextLine();
      assertEquals(nameRow, hintRow);
      assertFalse(name.visibleAt(nameRow));
      assertTrue(hint.visibleAt(hintRow));
      int modeRow = name.nextLine();
      assertEquals(hint.nextLine(), modeRow);
      assertTrue(name.visibleAt(modeRow));
   }

   @Test void wrappedHintsReserveSpaceEvenWhileItemNameIsVisible() {
      var layout = layout(false, true, false);
      int bottom = layout.reserveLines(3);
      assertFalse(layout.visibleAt(bottom));
      assertTrue(layout.nextLine() + 9 < bottom - 24);
   }

   @Test void actionBarTemporarilyOwnsBottomTextArea() {
      var layout = layout(true, false, true);
      assertFalse(layout.visibleAt(layout.nextLine()));
      assertFalse(layout.visibleAt(layout.nextLine()));
   }

   private static BottomHudLayout layout(boolean survival, boolean itemName, boolean actionBar) {
      return new BottomHudLayout(240, 39, 39, survival, itemName, actionBar);
   }
}
