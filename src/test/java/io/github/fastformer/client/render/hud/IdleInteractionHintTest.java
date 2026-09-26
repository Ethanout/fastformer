package io.github.fastformer.client.render.hud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleInteractionHintTest {
   @Test void hintMatchesTheAvailableAction() {
      assertEquals("fastformer.hud.idle_selection", IdleInteractionHint.key(true, false, true, false));
      assertEquals("fastformer.hud.idle_building", IdleInteractionHint.key(false, true, true, false));
      assertEquals("fastformer.hud.idle_aim", IdleInteractionHint.key(false, true, false, false));
      assertNull(IdleInteractionHint.key(false, false, true, false));
      assertNull(IdleInteractionHint.key(true, false, true, true));
      assertNull(IdleInteractionHint.key(false, true, true, true));
   }
}
