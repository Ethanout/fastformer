package io.github.fastformer.client.render.hud;

public final class IdleInteractionHint {
   private IdleInteractionHint() {}

   public static String key(boolean emptyHand, boolean placeable, boolean hasTarget, boolean vanillaOwnsPointer) {
      if (vanillaOwnsPointer || !emptyHand && !placeable) return null;
      if (!hasTarget) return "fastformer.hud.idle_aim";
      return emptyHand ? "fastformer.hud.idle_selection" : "fastformer.hud.idle_building";
   }
}
