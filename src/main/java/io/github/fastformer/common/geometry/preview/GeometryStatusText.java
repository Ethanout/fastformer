package io.github.fastformer.common.geometry.preview;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class GeometryStatusText {
   private GeometryStatusText() {
   }

   public static MutableComponent composeStageBar(
      MutableComponent stage,
      GeometryStageDisplay display,
      MutableComponent variant,
      boolean stageVisible,
      boolean modeVisible,
      boolean valueVisible,
      Styles styles
   ) {
      java.util.Objects.requireNonNull(styles, "styles");
      MutableComponent text = Component.empty();
      boolean hasText = false;
      if (stageVisible && GeometryTextBlock.hasContent(stage)) {
         text.append(stage.copy().withStyle(styles.text()));
         hasText = true;
      }

      GeometryStageDisplay safeDisplay = display == null ? GeometryStageDisplay.empty() : display;
      if (modeVisible) {
         if (safeDisplay.structured() && !safeDisplay.modes().isEmpty()) {
            if (hasText) {
               text.append(Component.literal("：").withStyle(styles.separator()));
            }
            appendModes(text, safeDisplay.modes(), styles);
            hasText = true;
         } else if (GeometryTextBlock.hasContent(variant)) {
            appendSeparator(text, hasText, styles);
            text.append(variant.copy().withStyle(styles.muted()));
            hasText = true;
         }
      }

      if (valueVisible && GeometryTextBlock.hasContent(safeDisplay.value())) {
         appendSeparator(text, hasText, styles);
         text.append(safeDisplay.value().copy().withStyle(styles.text()));
      }
      return text;
   }

   private static void appendModes(MutableComponent text, List<GeometryStageDisplay.Mode> modes, Styles styles) {
      for (int index = 0; index < modes.size(); index++) {
         if (index > 0) {
            text.append(Component.literal(" / ").withStyle(styles.separator()));
         }
         GeometryStageDisplay.Mode mode = modes.get(index);
         text.append(mode.label().copy().withStyle(
            mode.selected() ? styles.accent() : styles.muted()));
      }
   }

   private static void appendSeparator(MutableComponent text, boolean hasText, Styles styles) {
      if (hasText) {
         text.append(Component.literal(" ").withStyle(styles.separator()));
      }
   }

   public record Styles(Style text, Style muted, Style accent, Style separator) {
      public static final Styles UNSTYLED = new Styles(Style.EMPTY, Style.EMPTY, Style.EMPTY, Style.EMPTY);

      public Styles {
         java.util.Objects.requireNonNull(text, "text");
         java.util.Objects.requireNonNull(muted, "muted");
         java.util.Objects.requireNonNull(accent, "accent");
         java.util.Objects.requireNonNull(separator, "separator");
      }
   }
}
