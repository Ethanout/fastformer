package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.session.GeometrySystem;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class GeometryServerMode extends ServerMode {
   private final GeometrySystem geometry;
   private final GeometryMode mode;

   GeometryServerMode(GeometrySystem geometry, GeometryMode mode) {
      super("geometry/" + mode.name().toLowerCase(Locale.ROOT), InteractionState.GEOMETRY);
      this.geometry = geometry;
      this.mode = mode;
   }

   @Override public boolean active(ServerPlayer player) { return geometry.session(player).filter(draft -> draft.mode() == mode).isPresent(); }
   @Override public boolean allowsCommandAdjust(ServerPlayer player) { return geometry.allows(player, GeometryAction.SCALAR_ADJUST); }
   @Override public boolean allowsSubmode(ServerPlayer player) { return geometry.allows(player, GeometryAction.SUBMODE); }
   @Override public void sync(ServerPlayer player) { geometry.sync(player); }
   @Override public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) { return IntentResult.handled(geometry.cycleMode(player)); }
   @Override public void adjust(ServerPlayer player, int steps) { geometry.scroll(player, steps); }
   @Override public IntentResult confirm(ServerPlayer player) { return IntentResult.handled(geometry.fill(player)); }
   @Override public boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) { return point(player, hit, player.getEyePosition(), player.getViewVector(1.0F)); }
   @Override public boolean rightClickItem(ServerPlayer player, BlockHitResult hit) { return rightClickBlock(player, hit); }

   boolean point(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      if (geometry.confirmsOnRightClick(player)) return hit != null && geometry.fill(player);
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
      geometry.addPoint(player, GeometryHit.from(hit), eye, view);
      return true;
   }
}
