package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class SpecialItemHandlers {
   private static final Map<Item, UseOnBlock> HANDLERS = new HashMap<>();

   static {
      register(Items.DEBUG_STICK, SpecialItemHandlers::pickBlock);
   }

   private SpecialItemHandlers() {
   }

   public static void register(Item item, UseOnBlock handler) {
      HANDLERS.put(item, handler);
   }

   public static boolean isSpecial(ItemStack stack) {
      return HANDLERS.containsKey(stack.getItem());
   }

   public static boolean useOnBlock(ServerPlayer player, BlockPos pos) {
      UseOnBlock handler = HANDLERS.get(player.getMainHandItem().getItem());
      return handler != null && handler.use(player, pos);
   }

   private static boolean pickBlock(ServerPlayer player, BlockPos pos) {
      BlockState blockState = player.level().getBlockState(pos);
      ItemStack picked = blockState.getBlock().asItem().getDefaultInstance();
      if (picked.isEmpty() || picked.is(Items.AIR)) {
         FastPlaceMessages.actionBar(player, Component.translatable("fastformer.special.block_picker.no_item"));
         return false;
      }
      copyBlockState(blockState, picked);
      BlockEntity blockEntity = player.level().getBlockEntity(pos);
      if (blockEntity != null) {
         blockEntity.saveToItem(picked, player.registryAccess());
      }
      picked.setCount(picked.getMaxStackSize());
      player.getInventory().setItem(player.getInventory().selected, picked);
      player.getInventory().setChanged();
      player.containerMenu.broadcastChanges();
      FastPlaceMessages.actionBar(player, Component.translatable("fastformer.special.block_picker.picked", picked.getHoverName()));
      return true;
   }

   private static void copyBlockState(BlockState blockState, ItemStack stack) {
      BlockItemStateProperties properties = BlockItemStateProperties.EMPTY;
      for (Property<?> property : blockState.getProperties()) {
         properties = copyProperty(properties, blockState, property);
      }
      if (!properties.isEmpty()) {
         stack.set(DataComponents.BLOCK_STATE, properties);
      }
   }

   private static <T extends Comparable<T>> BlockItemStateProperties copyProperty(
      BlockItemStateProperties properties,
      BlockState blockState,
      Property<T> property
   ) {
      return properties.with(property, blockState.getValue(property));
   }

   @FunctionalInterface
   public interface UseOnBlock {
      boolean use(ServerPlayer player, BlockPos pos);
   }
}

