package io.github.fastformer.server.interaction.profile;

import io.github.fastformer.common.interaction.binding.SessionProfile;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Validates server-owned profile prerequisites against the captured and revalidated entry ray. */
public final class ProfileEntryRules {
   private ProfileEntryRules() {}

   public static boolean accepts(ServerPlayer player, SessionProfile profile, StartPlacementPayload.Target target, BlockHitResult hit) {
      if (SessionProfiles.NONE.equals(target.profile())) return true;
      if (!profile.session().toString().equals("fastformer:quick_shape")) return false;
      if (target.embedded() != profile.entry().anchor().equals("embedded") || target.anchorToken() != 0 || target.submitAfterEntry()) return false;
      for (String required : profile.entry().requires()) {
         boolean accepted = switch (required) {
            case "creative" -> player.isCreative();
            case "holding_placeable" -> PlaceableItems.isPlaceable(player.getMainHandItem());
            case "aiming_block" -> hit != null && hit.getType() == HitResult.Type.BLOCK && !player.level().getBlockState(hit.getBlockPos()).isAir();
            case "within_reach" -> hit != null && player.getEyePosition().distanceToSqr(hit.getLocation())
               <= player.blockInteractionRange() * player.blockInteractionRange();
            default -> false;
         };
         if (!accepted) return false;
      }
      return true;
   }
}
