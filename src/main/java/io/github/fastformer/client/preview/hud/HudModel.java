package io.github.fastformer.client.preview.hud;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import io.github.fastformer.client.feedback.ScrollFeedbackData;

/** Frozen text and colors for one visual frame. Drawing cannot advance interaction state. */
public record HudModel(boolean visible, float opacity, List<Text> topLeft, List<Text> topRight,
   List<Component> bottom, List<Line> crosshair, ScrollFeedbackData scroll, int scrollAlpha, int scrollColor) {
   public static final HudModel EMPTY = new HudModel(false, 1, List.of(), List.of(), List.of(), List.of(), null, 0, -1);

   public HudModel {
      if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) throw new IllegalArgumentException("Invalid HUD opacity");
      topLeft = List.copyOf(topLeft);
      topRight = List.copyOf(topRight);
      bottom = bottom.stream().map(Component::copy).map(Component.class::cast).toList();
      crosshair = List.copyOf(crosshair);
   }

   @Override public List<Component> bottom() { return bottom.stream().map(Component::copy).map(Component.class::cast).toList(); }

   public record Text(Component content, int y, int color) {
      public Text { content = Objects.requireNonNull(content, "content").copy(); }
      @Override public Component content() { return content.copy(); }
   }

   public record Run(Component content, int color, float scale, int gap) {
      public Run {
         content = Objects.requireNonNull(content, "content").copy();
         if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Invalid HUD text scale");
      }
      @Override public Component content() { return content.copy(); }
   }

   public record Line(List<Run> runs) {
      public Line { runs = List.copyOf(runs); }
   }
}
