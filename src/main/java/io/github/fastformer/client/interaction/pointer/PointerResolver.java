package io.github.fastformer.client.interaction.pointer;

import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** One environment's block hits, shared by input, HUD and world rendering. */
public final class PointerResolver {
   private final EnumMap<Policy, LongRangeBlockRaycast.Result> samples = new EnumMap<>(Policy.class);
   private View sampledView;

   public LongRangeBlockRaycast.Result sample(LocalPlayer player, boolean throughFluids, PointerRayPolicy policy) {
      return switch (policy) {
         case OUTLINE -> outline(player);
         case PLACEMENT -> placement(player);
         case SELECTION -> selection(player, throughFluids);
      };
   }

   public LongRangeBlockRaycast.Result outline(LocalPlayer player) {
      View view = view(player);
      return resolve(view, Policy.OUTLINE, () -> LongRangeBlockRaycast.clip(
         player.level(), player, view.eye(), view.direction()));
   }

   public LongRangeBlockRaycast.Result placement(LocalPlayer player) {
      View view = view(player);
      return resolve(view, Policy.PLACEMENT, () -> LongRangeBlockRaycast.clipForPlacement(
         player.level(), player, view.eye(), view.direction()));
   }

   public LongRangeBlockRaycast.Result selection(LocalPlayer player, boolean throughFluids) {
      if (InteractionRaycastRules.passesThroughFluids(player, throughFluids)) return outline(player);
      View view = view(player);
      return resolve(view, Policy.SELECTION, () -> LongRangeBlockRaycast.clipForSelection(
         player.level(), player, view.eye(), view.direction(), new InteractionRaycastRules(false, false)));
   }

   /** Frame, physical-event and tick boundaries discard hits even when the camera has not moved. */
   public void invalidate() {
      samples.clear();
      sampledView = null;
   }

   LongRangeBlockRaycast.Result resolve(View view, Policy policy, Supplier<LongRangeBlockRaycast.Result> raycast) {
      Objects.requireNonNull(view, "view");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(raycast, "raycast");
      if (!view.sameAs(sampledView)) {
         invalidate();
         sampledView = view;
      }
      LongRangeBlockRaycast.Result result = samples.get(policy);
      if (result == null) {
         result = Objects.requireNonNull(raycast.get(), "raycast result");
         samples.put(policy, result);
      }
      return result;
   }

   private static View view(LocalPlayer player) {
      Objects.requireNonNull(player, "player");
      return new View(player, player.level(), player.getEyePosition(), player.getViewVector(1.0F),
         player.level().getGameTime());
   }

   enum Policy { OUTLINE, PLACEMENT, SELECTION }

   /** Player/world are identity keys only; the sampler owns the actual game access. */
   record View(Object player, Object world, Vec3 eye, Vec3 direction, long tick) {
      View {
         Objects.requireNonNull(player, "player");
         Objects.requireNonNull(world, "world");
         Objects.requireNonNull(eye, "eye");
         Objects.requireNonNull(direction, "direction");
      }

      boolean sameAs(View other) {
         return other != null && player == other.player && world == other.world && tick == other.tick
            && eye.equals(other.eye) && direction.equals(other.direction);
      }
   }
}
