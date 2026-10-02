package io.github.fastformer.fastplace.placement;

import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.ShortWriteTransaction;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.input.ServerReachGate;
import io.github.fastformer.server.session.OperationManager;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

/** Places one creative-mode block through the shared reversible write path. */
public final class ForcedPlacement {
   private static final boolean TRACE = Boolean.getBoolean("fastformer.tracePlacement");
   private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private ForcedPlacement() { }

   public static boolean use(ServerPlayer player, BlockHitResult hit) {
      var settings = FastPlaceSettings.load(player);
      if (!player.isCreative() || !settings.enabled() || !settings.forcePlacement()
         || !PlaceableItems.isPlaceable(player.getMainHandItem())
         || OperationManager.active(player)
         || !ServerReachGate.vanillaAt(player, player.getEyePosition().distanceTo(hit.getLocation()))) return false;
      if (ServerInputDispatcher.interactionBlocked(player)) return true;
      var level = player.serverLevel();
      var snapshot = PlacementContextSnapshot.capture(level, player, player.getMainHandItem(), hit, false);
      var pos = snapshot.placementPosition();
      if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
         || !level.mayInteract(player, pos) || !player.canInteractWithBlock(pos, 0)) return true;
      var state = PlaceableItems.placementState(player.getMainHandItem(), player, snapshot).orElse(null);
      if (state == null || level.getBlockState(pos).equals(state)) return true;
      var outcome = ShortWriteTransaction.apply(player, level, Map.of(pos, state),
         PlacementUpdateMode.CLIENT_ONLY.flags(), PlacementUpdateMode.CLIENT_ONLY.flags());
      if (TRACE) LOGGER.info("Placement input: server time={}, tick={}, hit={}, target={}, state={}, outcome={}",
         System.nanoTime(), level.getGameTime(), hit.getBlockPos(), pos, state, outcome);
      ShortWriteTransaction.report(outcome, player);
      return true;
   }
}
