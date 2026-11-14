package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.interaction.InteractionState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** A registered mode owns its activity query and common interaction actions. */
public abstract class ServerMode {
   private final String id;
   private final InteractionState state;

   protected ServerMode(String id, InteractionState state) {
      this.id = id;
      this.state = state;
   }

   public final String id() { return id; }
   public final InteractionState interactionState() { return state; }
   public abstract boolean active(ServerPlayer player);
   public boolean acceptsHeldItem(ServerPlayer player) { return false; }
   public boolean allowsCommandAdjust(ServerPlayer player) { return true; }
   public boolean allowsSubmode(ServerPlayer player) { return true; }
   public boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) { return false; }
   public boolean rightClickItem(ServerPlayer player, BlockHitResult hit) { return false; }
   public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) { return IntentResult.pass(); }
   public void adjust(ServerPlayer player, int steps) {}
   public IntentResult confirm(ServerPlayer player) { return IntentResult.pass(); }
   public abstract void sync(ServerPlayer player);

   public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clip(player.level(), player, eye, view);
   }
}
