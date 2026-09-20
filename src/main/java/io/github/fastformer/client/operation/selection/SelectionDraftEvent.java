package io.github.fastformer.client.operation.selection;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/** A captured pointer event. The target cannot change before the event is applied. */
public record SelectionDraftEvent(Button button, BlockPos point, boolean alt) {
   public SelectionDraftEvent {
      Objects.requireNonNull(button, "button");
      point = Objects.requireNonNull(point, "point").immutable();
   }

   public static SelectionDraftEvent fromMouse(int button, BlockPos point, boolean alt) {
      if (point == null || button < 0 || button > 2) {
         return null;
      }
      return new SelectionDraftEvent(Button.values()[button], point, alt);
   }

   public enum Button { LEFT, RIGHT, MIDDLE }
}
