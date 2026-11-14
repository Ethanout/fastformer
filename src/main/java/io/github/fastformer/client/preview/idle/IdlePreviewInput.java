package io.github.fastformer.client.preview.idle;

import java.util.Objects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/** Pointer and placement facts captured by the client frame assembly. */
public record IdlePreviewInput(Player player, BlockHitResult hit, boolean embedded,
   boolean passesThroughFluids, boolean renderBlockModel) {
   public IdlePreviewInput {
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(hit, "hit");
   }
}
