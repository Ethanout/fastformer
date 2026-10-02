package io.github.fastformer.client.placement;

import io.github.fastformer.client.input.mouse.UsePressSequence;
import io.github.fastformer.client.interaction.ClientInteractionUpdates;
import io.github.fastformer.client.interaction.ClientReachGate;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/** The server owns forced block writes, including their placement target. */
public final class ClientForcedPlacement {
   private static final boolean TRACE = Boolean.getBoolean("fastformer.tracePlacement");
   private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private static final Map<LocalPlayer, UsePressSequence> PRESSES = new WeakHashMap<>();
   private ClientForcedPlacement() { }

   public static void input(LocalPlayer player, int action) {
      if (player == null) return;
      var press = PRESSES.computeIfAbsent(player, ignored -> new UsePressSequence());
      if (action == 1) press.press();
      else if (action == 0) press.release();
      if (TRACE && (action == 0 || action == 1)) LOGGER.info("Placement input: physical={}, time={}, vanilla={}",
         action == 1 ? "press" : "release", System.nanoTime(), ClientReachGate.vanilla());
   }

   public static boolean consumes(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
      return hand == InteractionHand.MAIN_HAND && player.isCreative()
         && ClientInteractionUpdates.forcePlacement(player) && !QuickReplaceMode.active()
         && !FastPlaceClientPreview.operationActive() && PlaceableItems.isPlaceable(player.getMainHandItem())
         && ClientReachGate.vanillaAt(player, player.getEyePosition().distanceTo(hit.getLocation()));
   }

   public static boolean accept(LocalPlayer player) {
      boolean accepted = PRESSES.computeIfAbsent(player, ignored -> new UsePressSequence()).accept();
      if (TRACE) LOGGER.info("Placement input: use={}, time={}, vanilla={}, hit={}",
         accepted ? "accepted" : "repeated", System.nanoTime(), ClientReachGate.vanilla(),
         net.minecraft.client.Minecraft.getInstance().hitResult == null ? null
            : net.minecraft.client.Minecraft.getInstance().hitResult.getLocation());
      return accepted;
   }
}
