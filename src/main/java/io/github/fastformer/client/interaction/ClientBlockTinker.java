package io.github.fastformer.client.interaction;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.interaction.BlockTinker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

public final class ClientBlockTinker {
   private ClientBlockTinker() {}

   public static boolean consumes(Player player, InteractionHand hand, BlockHitResult hit) {
      if (hand != InteractionHand.MAIN_HAND || !ClientInteractionUpdates.wrenchEnabled(player)
         || FastPlaceClientPreview.active() || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive() || FastPlaceClientPreview.taskActive()) return false;
      return !BlockTinker.preview(player, hit).isEmpty();
   }
}
