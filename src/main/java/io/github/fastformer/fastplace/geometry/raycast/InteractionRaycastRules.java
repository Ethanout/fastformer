package io.github.fastformer.fastplace.geometry.raycast;

/** Frozen targeting rules of one interaction, retained when it becomes a double-click entry. */
public record InteractionRaycastRules(boolean embedded, boolean throughFluids) {
   public static final InteractionRaycastRules DEFAULT = new InteractionRaycastRules(false, false);
   public static final InteractionRaycastRules VANILLA_OUTLINE = new InteractionRaycastRules(false, true);

   public static boolean passesThroughFluids(net.minecraft.world.entity.Entity source, boolean requested) {
      return requested || source != null && source.isInWater();
   }
}
