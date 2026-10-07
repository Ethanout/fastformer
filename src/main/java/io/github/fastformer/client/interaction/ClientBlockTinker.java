package io.github.fastformer.client.interaction;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.interaction.BlockTinker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

public final class ClientBlockTinker {
   private ClientBlockTinker() {}

   public static boolean consumes(Player player, InteractionHand hand, BlockHitResult hit) {
      return hand == InteractionHand.MAIN_HAND && available(player)
         && BlockTinker.consumes(player, hit);
   }

   public static boolean available(Player player) {
      return BlockTinker.canEdit(player) && ClientInteractionUpdates.wrenchEnabled(player)
         && io.github.fastformer.client.input.FastPlaceClientInput.vanillaSession()
         && !FastPlaceClientPreview.active() && !FastPlaceClientPreview.operationActive()
         && !FastPlaceClientPreview.geometryActive() && !FastPlaceClientPreview.taskActive();
   }
}
