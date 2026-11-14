package io.github.fastformer.client.preview.hud;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class HudModelTest {
   @Test void capturedTextAndListsDoNotFollowMutableSourcesOrConsumers() {
      var text = Component.literal("initial");
      var bottom = new ArrayList<Component>();
      bottom.add(text);
      var model = new HudModel(true, 1, List.of(new HudModel.Text(text, 8, -1)), List.of(),
         bottom, List.of(new HudModel.Line(List.of(new HudModel.Run(text, -1, 1, 0)))), null, 0, -1);
      text.append(" changed");
      bottom.clear();
      ((net.minecraft.network.chat.MutableComponent)model.bottom().getFirst()).append(" consumer");
      assertEquals("initial", model.bottom().getFirst().getString());
      assertEquals("initial", model.topLeft().getFirst().content().getString());
      assertEquals("initial", model.crosshair().getFirst().runs().getFirst().content().getString());
      assertThrows(UnsupportedOperationException.class, () -> model.crosshair().clear());
   }

   @Test void invisibleFrameContainsNoPreviouslyCapturedContent() {
      assertFalse(HudModel.EMPTY.visible());
      assertTrue(HudModel.EMPTY.bottom().isEmpty());
      assertTrue(SelectionHudModel.EMPTY.labels().isEmpty());
      assertTrue(SelectionHudModel.EMPTY.corners().isEmpty());
   }
}
