package io.github.fastformer.common.geometry.gizmo;

import java.util.Objects;

/** Theme-independent intent for a handle's normal, hovered and active appearance. */
public sealed interface HoverFeedback permits HoverFeedback.AxisColors, HoverFeedback.PointColors, HoverFeedback.ExplicitColors {
   static HoverFeedback axis() { return AxisColors.INSTANCE; }

   static HoverFeedback pointGizmo(HoverFeedback normal) { return new PointColors(normal); }

   enum AxisColors implements HoverFeedback { INSTANCE }

   record PointColors(HoverFeedback normal) implements HoverFeedback {
      public PointColors {
         Objects.requireNonNull(normal, "normal");
         if (normal instanceof PointColors point) normal = point.normal();
      }
   }

   /** Explicit RGB overrides retain the existing rule that active uses the hover color. */
   record ExplicitColors(int normalColor, int hoverColor, int activeColor) implements HoverFeedback {
      public ExplicitColors {
         normalColor &= 0xFFFFFF;
         hoverColor &= 0xFFFFFF;
         activeColor = hoverColor;
      }
   }
}
