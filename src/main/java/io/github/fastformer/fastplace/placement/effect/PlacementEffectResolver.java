package io.github.fastformer.fastplace.placement.effect;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.session.FastPlaceSession;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/** Builds the one authoritative effect context used by placement and preview sync. */
public final class PlacementEffectResolver {
   private PlacementEffectResolver() {
   }

   public static Optional<ResolvedPlacementEffect> resolve(
      Player player,
      FastPlaceSettings settings,
      FastPlaceSession session,
      BlockState prototype,
      FastPlaceGeometry.Modes modes
   ) {
      PlacementContextSnapshot placementContext = session.placementContext();
      Direction.Axis baseAxis = placementContext == null
         ? Direction.Axis.Y
         : placementContext.clickedFace().getAxis();
      return PlacementEffectRegistry.resolve(
         settings,
         new PlacementEffectContext(
            player,
            player.getMainHandItem(),
            prototype,
            baseAxis,
            session.points(),
            modes,
            session.polygonHeightConfirmed(),
            session.polygonVolumeShape(),
            placementContext
         )
      );
   }
}
