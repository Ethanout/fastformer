package io.github.fastformer.server.specialitem;

import io.github.fastformer.server.feedback.PlayerMessages;



import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
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

@EventBusSubscriber(modid = "fastformer")
public final class SpecialItemHandlers {
   private static final SpecialItemRegistry HANDLERS = new SpecialItemRegistry();

   static {
      register(ResourceLocation.withDefaultNamespace("debug_stick"), SpecialItemHandlers::pickBlock);
   }

   private SpecialItemHandlers() {
   }

   /** Add-ons register during mod construction; duplicate items and late registration are errors. */
   public static void register(Item item, UseOnBlock handler) {
      ResourceLocation id = BuiltInRegistries.ITEM.getKey(java.util.Objects.requireNonNull(item, "item"));
      if (BuiltInRegistries.ITEM.get(id) != item) throw new IllegalArgumentException("Special item is not registered: " + item);
      register(id, handler);
   }

   public static void register(ResourceLocation item, UseOnBlock handler) {
      HANDLERS.register(item, handler);
   }

   @SubscribeEvent
   public static void freeze(FMLCommonSetupEvent event) {
      HANDLERS.freeze();
   }

   public static boolean isSpecial(ItemStack stack) {
      return !stack.isEmpty() && HANDLERS.handler(BuiltInRegistries.ITEM.getKey(stack.getItem())).isPresent();
   }

   public static boolean useOnBlock(ServerPlayer player, BlockPos pos) {
      UseOnBlock handler = HANDLERS.handler(BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())).orElse(null);
      return handler != null && handler.use(player, pos);
   }

   private static boolean pickBlock(ServerPlayer player, BlockPos pos) {
      BlockState blockState = player.level().getBlockState(pos);
      ItemStack picked = blockState.getBlock().asItem().getDefaultInstance();
      if (picked.isEmpty() || picked.is(Items.AIR)) {
         PlayerMessages.actionBar(player, Component.translatable("fastformer.special.block_picker.no_item"));
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
      PlayerMessages.actionBar(player, Component.translatable("fastformer.special.block_picker.picked", picked.getHoverName()));
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

