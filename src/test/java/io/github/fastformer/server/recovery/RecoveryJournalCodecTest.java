package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class RecoveryJournalCodecTest {
   @Test
   void missingIndicesCannotSilentlyRestoreEveryCellAsPaletteZero() {
      assertThrows(IOException.class, () -> RecoveryJournalCodec.paletteIds(new CompoundTag(), "Before", 10));
   }

   @Test
   void conflictingLayoutsAreRejectedInsteadOfChoosingOne() {
      CompoundTag root = new CompoundTag();
      root.putInt("AfterUniformPaletteId", 0);
      root.putIntArray("AfterPaletteIds", new int[]{1});
      assertThrows(IOException.class, () -> RecoveryJournalCodec.paletteIds(root, "After", 1));
   }

   @Test
   void wrongNbtTypesCannotDefaultToPaletteZero() {
      CompoundTag root = new CompoundTag();
      root.putString("BeforeUniformPaletteId", "0");
      assertThrows(IOException.class, () -> RecoveryJournalCodec.paletteIds(root, "Before", 1));
      root.remove("BeforeUniformPaletteId");
      root.putString("BeforePaletteIds", "0");
      assertThrows(IOException.class, () -> RecoveryJournalCodec.paletteIds(root, "Before", 1));
   }
}
