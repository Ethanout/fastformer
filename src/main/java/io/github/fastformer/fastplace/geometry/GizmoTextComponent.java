package io.github.fastformer.fastplace.geometry;

public record GizmoTextComponent(
   GizmoTextTemplate hoverTemplate,
   GizmoTextTemplate dragTemplate,
   boolean hoverVisible,
   boolean dragVisible
) {
   public GizmoTextComponent {
      hoverTemplate = hoverTemplate == null ? new GizmoTextTemplate("") : hoverTemplate;
      dragTemplate = dragTemplate == null ? new GizmoTextTemplate("") : dragTemplate;
   }

   public static GizmoTextComponent none() {
      return new GizmoTextComponent(new GizmoTextTemplate(""), new GizmoTextTemplate(""), false, false);
   }

   public static GizmoTextComponent pointLevel() {
      return new GizmoTextComponent(
         new GizmoTextTemplate("${axis} ${operation}"),
         new GizmoTextTemplate("${axis} ${base}${delta}"),
         true,
         true
      );
   }

   public String render(GizmoTextContext context, boolean dragging) {
      if (dragging) {
         return this.dragVisible ? this.dragTemplate.render(context) : "";
      }
      return this.hoverVisible ? this.hoverTemplate.render(context) : "";
   }

   public GizmoTextComponent withHoverVisible(boolean visible) {
      return new GizmoTextComponent(this.hoverTemplate, this.dragTemplate, visible, this.dragVisible);
   }

   public GizmoTextComponent withDragVisible(boolean visible) {
      return new GizmoTextComponent(this.hoverTemplate, this.dragTemplate, this.hoverVisible, visible);
   }

   public boolean empty() {
      return this.hoverTemplate.template().isBlank() && this.dragTemplate.template().isBlank();
   }
}
