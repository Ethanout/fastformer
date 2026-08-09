package io.github.fastformer.fastplace.geometry;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

public final class GeometryStatusText {
   private GeometryStatusText() {
   }

   public static MutableComponent composeStageBar(
      MutableComponent stage,
      GeometryStageDisplay display,
      MutableComponent variant,
      boolean stageVisible,
      boolean modeVisible,
      boolean valueVisible
   ) {
      MutableComponent text = Component.empty();
      boolean hasText = false;
      if (stageVisible && GeometryTextBlock.hasContent(stage)) {
         text.append(stage.copy().withStyle(ChatFormatting.WHITE));
         hasText = true;
      }

      GeometryStageDisplay safeDisplay = display == null ? GeometryStageDisplay.empty() : display;
      if (modeVisible) {
         if (safeDisplay.structured() && !safeDisplay.modes().isEmpty()) {
            if (hasText) {
               text.append(Component.literal("：").withStyle(ChatFormatting.DARK_GRAY));
            }
            appendModes(text, safeDisplay.modes());
            hasText = true;
         } else if (GeometryTextBlock.hasContent(variant)) {
            appendSeparator(text, hasText);
            text.append(variant.copy().withStyle(ChatFormatting.GRAY));
            hasText = true;
         }
      }

      if (valueVisible && GeometryTextBlock.hasContent(safeDisplay.value())) {
         appendSeparator(text, hasText);
         text.append(safeDisplay.value().copy().withStyle(ChatFormatting.WHITE));
      }
      return text;
   }

   private static void appendModes(MutableComponent text, List<GeometryStageDisplay.Mode> modes) {
      for (int index = 0; index < modes.size(); index++) {
         if (index > 0) {
            text.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
         }
         GeometryStageDisplay.Mode mode = modes.get(index);
         ChatFormatting color = mode.selected() ? ChatFormatting.GREEN : ChatFormatting.GRAY;
         text.append(mode.label().copy().withStyle(color));
      }
   }

   private static void appendSeparator(MutableComponent text, boolean hasText) {
      if (hasText) {
         text.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
      }
   }

}
