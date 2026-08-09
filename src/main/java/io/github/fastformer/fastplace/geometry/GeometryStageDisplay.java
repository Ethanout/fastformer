package io.github.fastformer.fastplace.geometry;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public record GeometryStageDisplay(
   boolean structured,
   List<Mode> modes,
   MutableComponent value
) {
   public GeometryStageDisplay {
      modes = modes == null ? List.of() : List.copyOf(modes);
      value = value == null ? Component.empty() : value;
   }

   public static GeometryStageDisplay empty() {
      return new GeometryStageDisplay(false, List.of(), Component.empty());
   }

   public static GeometryStageDisplay quiet() {
      return new GeometryStageDisplay(true, List.of(), Component.empty());
   }

   public static GeometryStageDisplay modes(List<Mode> modes) {
      return new GeometryStageDisplay(true, modes, Component.empty());
   }

   public static GeometryStageDisplay value(MutableComponent value) {
      return new GeometryStageDisplay(true, List.of(), value);
   }

   public record Mode(MutableComponent label, boolean selected) {
      public Mode {
         label = label == null ? Component.empty() : label;
      }
   }
}
