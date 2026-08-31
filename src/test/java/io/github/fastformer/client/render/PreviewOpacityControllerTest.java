package io.github.fastformer.client.render;

import io.github.fastformer.client.render.PreviewOpacityController;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PreviewOpacityControllerTest {
   @Test
   void callerControlsFadeTriggerFloorAndForcedVisibility() {
      PreviewOpacityController controller = new PreviewOpacityController(100L, true);
      PreviewOpacityController.Input visible = PreviewOpacityController.Input.visible();
      PreviewOpacityController.Input faded = new PreviewOpacityController.Input(true, false, 0.30F);

      assertEquals(1.0F, controller.update(visible, 0L), 0.0001F);
      assertEquals(0.65F, controller.update(faded, 50L), 0.0001F);
      assertEquals(1.0F, controller.update(new PreviewOpacityController.Input(true, true, 0.30F), 60L), 0.0001F);
      assertEquals(1.0F, controller.update(faded, 70L), 0.0001F);
      assertEquals(0.65F, controller.update(faded, 120L), 0.0001F);
   }
}
