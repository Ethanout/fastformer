package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.server.geometry.workflow.GeometryWorkflowEditors;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.common.mode.BuiltinModes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class GeometryServerMode extends ServerMode<GeometrySession> {
   private final GeometrySystem geometry;
   private final GeometryMode mode;

   GeometryServerMode(GeometrySystem geometry, GeometryMode mode) {
      super(BuiltinModes.geometry(mode), InteractionState.GEOMETRY, geometry);
      this.geometry = geometry;
      this.mode = mode;
   }

   @Override protected boolean owns(GeometrySession session) { return session.mode() == mode; }
   @Override protected void onOpen(ServerPlayer player, GeometrySession session) { geometry.initialize(player, session); }
   @Override protected boolean allowsCommandAdjust(ServerPlayer player, GeometrySession session) {
      return GeometryWorkflowEditors.get(mode).allows(session, GeometryAction.SCALAR_ADJUST);
   }
   @Override protected boolean allowsSubmode(ServerPlayer player, GeometrySession session) {
      return GeometryWorkflowEditors.get(mode).allows(session, GeometryAction.SUBMODE);
   }
   @Override protected void onPreview(ServerPlayer player, GeometrySession session) { PreviewSync.syncGeometry(player, session); }
   @Override protected IntentResult onCycle(ServerPlayer player, GeometrySession session, BlockPos lineCandidate) { return IntentResult.handled(geometry.cycleMode(player)); }
   @Override protected void onAdjust(ServerPlayer player, GeometrySession session, int steps) { geometry.scroll(player, steps); }
   @Override protected IntentResult onConfirm(ServerPlayer player, GeometrySession session) { return IntentResult.handled(geometry.fill(player)); }
   @Override protected boolean onRightClickBlock(ServerPlayer player, GeometrySession session, BlockHitResult hit) { return point(player, hit, player.getEyePosition(), player.getViewVector(1.0F)); }
   @Override protected boolean onRightClickItem(ServerPlayer player, GeometrySession session, BlockHitResult hit) { return onRightClickBlock(player, session, hit); }

   boolean point(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      if (geometry.confirmsOnRightClick(player)) return hit != null && geometry.fill(player);
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
      geometry.addPoint(player, GeometryHit.from(hit), eye, view);
      return true;
   }
}
