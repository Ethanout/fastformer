package io.github.fastformer.client.session;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict reads for durable client data; malformed lists must not become empty state. */
final class ClientStorageNbt {
   private ClientStorageNbt() { }

   static ListTag requiredCompoundList(CompoundTag owner, String key) throws IOException {
      if (!(owner.get(key) instanceof ListTag list)
         || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
         throw new IOException("Client storage has a missing or invalid compound list: " + key);
      }
      return list;
   }
}
