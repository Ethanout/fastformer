package io.github.fastformer.client.render;

import io.github.fastformer.client.input.OperationInteractionIntent;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * Decides which action prompts the operation pointer may show.
 * A prompt must not promise an action that the click handler refuses, so a
 * submission-locked workspace shows no action prompt at all.
 */
public final class WorkspacePointerPrompt {
   private WorkspacePointerPrompt() {
   }

   /** A submission-locked workspace refuses every new pointer action until the result arrives. */
   public static boolean acceptsNewAction(boolean workspaceLocked) {
      return !workspaceLocked;
   }

   /**
    * Crosshair lines for the resolved target.
    * The result is empty while the workspace refuses new actions, because the
    * face drag and the selection click both stop on the same lock.
    */
   public static List<Component> crosshairActionLines(
      OperationInteractionIntent target, boolean workspaceLocked
   ) {
      if (!acceptsNewAction(workspaceLocked)) {
         return List.of();
      }
      return target instanceof OperationInteractionIntent.Face face
         ? face.requireHoverTextLines()
         : List.of();
   }
}
