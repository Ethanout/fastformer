package io.github.fastformer.client.operation.clipboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;

/** Client-wide clipboard storage; publishes memory only after a successful disk write. */
public final class OperationClipboardRepository {
   private final OperationClipboardState state = new OperationClipboardState();
   private final Supplier<Path> file;

   public OperationClipboardRepository() {
      this(() -> FMLPaths.CONFIGDIR.get().resolve("fastformer-operation-clipboard.nbt.gz"));
   }

   public OperationClipboardRepository(Supplier<Path> file) {
      this.file = Objects.requireNonNull(file, "file");
   }

   public void save(OperationClipboard clipboard) throws IOException {
      state.saveAndPublish(clipboard, value -> OperationClipboardStore.save(file.get(), OperationClipboardCodec.encode(value)));
   }

   public Optional<OperationClipboard> load(HolderGetter<Block> blocks) throws IOException {
      return state.load(() -> {
         var root = OperationClipboardStore.loadStrict(file.get());
         return root.isEmpty() ? Optional.empty() : Optional.of(OperationClipboardCodec.decode(root.get(), blocks));
      });
   }
}
