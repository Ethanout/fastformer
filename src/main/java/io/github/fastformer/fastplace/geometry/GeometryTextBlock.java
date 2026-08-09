package io.github.fastformer.fastplace.geometry;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;

public record GeometryTextBlock(String id, Placement placement, Component content, boolean visible) {
   public static final String STATUS_ID = "status";
   public static final String STAGE_ID = "stage";
   public static final String MODE_ID = "mode";
   public static final String VALUE_ID = "value";
   public static final String HINT_ID = "hint";

   public GeometryTextBlock {
      id = id == null || id.isBlank() ? "text" : id;
      placement = placement == null ? Placement.BOTTOM_CENTER : placement;
      content = content == null ? Component.empty() : content;
   }

   public static GeometryTextBlock bottomCenter(String id, Component content) {
      return new GeometryTextBlock(id, Placement.BOTTOM_CENTER, content, true);
   }

   public static GeometryTextBlock bottomHint(String id, Component content) {
      return new GeometryTextBlock(id, Placement.BOTTOM_HINT, content, true);
   }

   public static GeometryTextBlock hidden(String id, Placement placement) {
      return new GeometryTextBlock(id, placement, Component.empty(), false);
   }

   public GeometryTextBlock withVisible(boolean visible) {
      return new GeometryTextBlock(this.id, this.placement, this.content, visible);
   }

   public static boolean hasContent(Component content) {
      if (content == null) {
         return false;
      }
      if (!content.getSiblings().isEmpty()) {
         return true;
      }
      if (content.getContents() instanceof PlainTextContents plain) {
         return !plain.text().isBlank();
      }
      return true;
   }

   public enum Placement {
      TOP_LEFT,
      TOP_RIGHT,
      BOTTOM_CENTER,
      BOTTOM_HINT,
      CROSSHAIR
   }
}
