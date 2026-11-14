package io.github.fastformer.client.preview.hud;

import java.util.List;
import net.minecraft.network.chat.Component;

/** Projected smart-selection marks; coordinates and hysteresis are already resolved. */
public record SelectionHudModel(List<Label> labels, List<Corner> corners, List<Component> bottom) {
   public static final SelectionHudModel EMPTY = new SelectionHudModel(List.of(), List.of(), List.of());
   public SelectionHudModel {
      labels = List.copyOf(labels);
      corners = List.copyOf(corners);
      bottom = bottom.stream().map(Component::copy).map(Component.class::cast).toList();
   }
   @Override public List<Component> bottom() { return bottom.stream().map(Component::copy).map(Component.class::cast).toList(); }
   public record Label(Component text, double x, double y, int color) {
      public Label { text = text.copy(); }
      @Override public Component text() { return text.copy(); }
   }
   public record Corner(double x, double y, int dx, int dy, int thickness, double scale, int color) {}
}
