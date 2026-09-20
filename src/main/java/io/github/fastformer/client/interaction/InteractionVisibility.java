package io.github.fastformer.client.interaction;

/** Display and picking use the same policy; selection remains owned by the session. */
public enum InteractionVisibility {
   ALWAYS,
   WHEN_SELECTED,
   HIDDEN;

   public static boolean isVisible(InteractionObject object, boolean selected) {
      if (object == null) return false;
      return switch (object.component(InteractionComponents.VISIBILITY).orElse(ALWAYS)) {
         case ALWAYS -> true;
         case WHEN_SELECTED -> selected;
         case HIDDEN -> false;
      };
   }
}
