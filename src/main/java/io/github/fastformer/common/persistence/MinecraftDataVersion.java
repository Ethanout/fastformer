package io.github.fastformer.common.persistence;

import java.io.IOException;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Validates stored game versions before decoding or applying any world data. */
public final class MinecraftDataVersion {
   private MinecraftDataVersion() {}

   public static int current() {
      return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
   }

   public static int read(CompoundTag root) throws IOException {
      if (!root.contains("DataVersion", Tag.TAG_INT)) {
         throw new IncompatibleWorldDataException("Missing Minecraft DataVersion");
      }
      int version = root.getInt("DataVersion");
      requireCurrent(version);
      return version;
   }

   private static void requireCurrent(int sourceVersion) throws IOException {
      if (sourceVersion != current()) {
         throw new IncompatibleWorldDataException("Unsupported Minecraft DataVersion: " + sourceVersion);
      }
   }
}
