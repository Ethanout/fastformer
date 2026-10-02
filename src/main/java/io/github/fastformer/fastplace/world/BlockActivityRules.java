package io.github.fastformer.fastplace.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** World-wide building rules, stored independently of the server tick rate. */
public final class BlockActivityRules extends SavedData {
   private boolean fallingDisabled;

   public static BlockActivityRules get(MinecraftServer server) {
      return server.overworld().getDataStorage().computeIfAbsent(
         new Factory<>(BlockActivityRules::new, BlockActivityRules::load, null), "fastformer_block_activity");
   }

   private static BlockActivityRules load(CompoundTag tag, HolderLookup.Provider registries) {
      BlockActivityRules rules = new BlockActivityRules();
      rules.fallingDisabled = tag.getBoolean("fallingDisabled");
      return rules;
   }

   public boolean fallingDisabled() { return fallingDisabled; }

   public void setFallingDisabled(boolean disabled) {
      if (fallingDisabled == disabled) return;
      fallingDisabled = disabled;
      setDirty();
   }

   @Override
   public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
      tag.putBoolean("fallingDisabled", fallingDisabled);
      return tag;
   }
}
