package io.github.fastformer.server.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Returns an intercepted vanilla toss without losing a stack when its selected slot is occupied. */
public final class TossedItems {
   private TossedItems() {}

   public static void restore(ServerPlayer player, ItemStack tossed) {
      if (tossed.isEmpty()) return;
      Inventory inventory = player.getInventory();
      ItemStack selected = inventory.getSelected();
      ItemStack returning = tossed.copy();
      if (selected.isEmpty()) {
         inventory.setItem(inventory.selected, returning);
      } else if (ItemStack.isSameItemSameComponents(selected, returning)
         && selected.getCount() + returning.getCount() <= selected.getMaxStackSize()) {
         selected.grow(returning.getCount());
         inventory.setItem(inventory.selected, selected);
      } else if (!inventory.add(returning)) {
         player.drop(returning, false);
      }
      inventory.setChanged();
      player.containerMenu.broadcastChanges();
   }
}
