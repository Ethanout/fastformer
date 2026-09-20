package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientOperationDraftCodecTest {
   @Test
   void roundTripPreservesSelectionTransformAndSourceData() throws IOException {
      ClientOperationDraft original = sampleDraft();

      ClientOperationDraft decoded = ClientOperationDraftCodec.decode(
         ClientOperationDraftCodec.encode(original), null
      );

      assertEquals(original, decoded);
   }

   @Test
   void decodeRejectsUnknownEnumsAndNonFiniteTransforms() {
      CompoundTag unknownMode = ClientOperationDraftCodec.encode(sampleDraft());
      unknownMode.getCompound("Identity").putString("Mode", "UNKNOWN");
      assertThrows(IOException.class, () -> ClientOperationDraftCodec.decode(unknownMode, null));

      CompoundTag nonFinite = ClientOperationDraftCodec.encode(sampleDraft());
      ListTag parts = nonFinite.getCompound("Workspace").getList("Parts", Tag.TAG_COMPOUND);
      parts.getCompound(0).getCompound("Transform").getCompound("Translation").putDouble("X", Double.NaN);
      assertThrows(IOException.class, () -> ClientOperationDraftCodec.decode(nonFinite, null));
   }

   @Test
   void decodeRejectsBlockDataWithoutARegistry() {
      CompoundTag root = ClientOperationDraftCodec.encode(sampleDraft());
      ListTag parts = root.getCompound("Workspace").getList("Parts", Tag.TAG_COMPOUND);
      ListTag blocks = parts.getCompound(0).getList("Blocks", Tag.TAG_COMPOUND);
      CompoundTag block = new CompoundTag();
      block.putLong("Pos", BlockPos.ZERO.asLong());
      CompoundTag state = new CompoundTag();
      state.putString("Name", "minecraft:stone");
      block.put("State", state);
      blocks.add(block);

      assertThrows(IOException.class, () -> ClientOperationDraftCodec.decode(root, null));
   }

   private static ClientOperationDraft sampleDraft() {
      BlockPos min = new BlockPos(-4, 20, 7);
      BlockPos max = new BlockPos(2, 24, 9);
      OperationSelectionVolume volume = OperationSelectionVolume.cuboid(min, max, min, max);
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, volume, java.util.Map.of(), WorkspaceTransform.IDENTITY, true
      ).withTranslation(new Vec3(3.0, -1.0, 8.0));
      OperationDraftIdentity identity = new OperationDraftIdentity(
         OperationSelectionMode.CUBOID, List.of(min, max), 0,
         BlockPos.ZERO, BlockPos.ZERO, 0.0, min, max
      );
      return new ClientOperationDraft(
         identity,
         new ClientOperationWorkspace.DraftState(List.of(part), Set.of(1), 1),
         new ClientSelectionSession.DraftState(
            OperationSelectionMode.CUBOID, List.of(min, max), 0, min, max
         )
      );
   }

   @Test
   void decodeRejectsMissingOrUnsupportedVersions() {
      CompoundTag missing = new CompoundTag();
      assertThrows(IOException.class, () ->
         ClientOperationDraftCodec.decode(missing, null));

      missing.putInt("Version", ClientOperationDraft.CURRENT_VERSION + 1);
      assertThrows(IOException.class, () ->
         ClientOperationDraftCodec.decode(missing, null));
   }
}
