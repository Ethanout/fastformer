package io.github.fastformer.client.render.model;

import java.util.List;

public record ScrollFeedbackData(List<AxisFeedback> axes, String text) {
   public ScrollFeedbackData {
      axes = axes == null ? List.of() : List.copyOf(axes);
      text = text == null ? "" : text;
   }
}
