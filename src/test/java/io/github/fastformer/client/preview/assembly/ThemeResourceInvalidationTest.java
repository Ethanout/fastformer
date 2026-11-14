package io.github.fastformer.client.preview.assembly;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThemeResourceInvalidationTest {
   @Test void eachNewRevisionInvalidatesPlansPreviewAndGpuOnceInOrder() {
      var gate = new ThemeResourceInvalidation();
      var calls = new ArrayList<String>();
      Runnable plan = () -> calls.add("plan");
      Runnable preview = () -> calls.add("preview");
      Runnable gpu = () -> calls.add("gpu");
      gate.update(10, plan, preview, gpu);
      gate.update(10, plan, preview, gpu);
      gate.update(11, plan, preview, gpu);
      assertEquals(List.of("plan", "preview", "gpu", "plan", "preview", "gpu"), calls);
   }

   @Test void failedInvalidationIsRetriedBeforeARevisionIsAccepted() {
      var gate = new ThemeResourceInvalidation();
      var calls = new ArrayList<String>();
      assertThrows(IllegalStateException.class, () -> gate.update(10, () -> calls.add("plan"),
         () -> { throw new IllegalStateException("preview unavailable"); }, () -> calls.add("gpu")));
      gate.update(10, () -> calls.add("plan"), () -> calls.add("preview"), () -> calls.add("gpu"));
      assertEquals(List.of("plan", "plan", "preview", "gpu"), calls);
   }
}
