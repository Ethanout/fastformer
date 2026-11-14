package io.github.fastformer.server.recovery;

import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_CORRECTION;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_MANIFEST;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_SEAL;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.decode;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.decodeCorrections;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.RecoveryJournalCodec.DecodedCorrections;
import io.github.fastformer.server.recovery.RecoveryJournalCodec.DecodedJournal;
import io.github.fastformer.server.recovery.RecoveryJournalCodec.JournalPair;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Replays sealed operations forward and rolls back unsealed operations in reverse order. */
final class JournalReplayer {
   private static final Logger LOGGER = LogUtils.getLogger();

   private JournalReplayer() {}

   static StartupRecoveryAction startupRecoveryAction(boolean matchesBefore, boolean matchesAfter) {
      if (matchesBefore) {
         return StartupRecoveryAction.ALREADY_RESTORED;
      }
      return matchesAfter ? StartupRecoveryAction.RESTORE : StartupRecoveryAction.PRESERVE_EXTERNAL;
   }

   static boolean restore(MinecraftServer server, Path directory) {
      try {
         if (!RecoveryJournalSegments.validateSegmentDirectories(List.of(directory))) return false;
         var manifest = RecoveryJournalManifest.read(directory.resolve(JOURNAL_MANIFEST));
         boolean sealed = Files.exists(directory.resolve(JOURNAL_SEAL));
         var segments = sealed
            ? RecoveryJournalSegments.inspectComplete(directory, manifest.operationId(),
               RecoveryJournalSeal.read(directory.resolve(JOURNAL_SEAL), manifest.operationId()).segmentCount())
            : RecoveryJournalSegments.inspectUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
         if (sealed) {
            var seal = RecoveryJournalSeal.read(directory.resolve(JOURNAL_SEAL), manifest.operationId());
            if (segments.digest() != seal.digest()) throw new IOException("Segmented recovery journal digest mismatch");
         }
         ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
         ServerLevel level = server.getLevel(dimension);
         if (level == null) throw new IOException("Journal dimension is not loaded: " + manifest.dimension());
         DecodedCorrections corrections = decodeCorrections(
               level,
               manifest.operationId(),
               segments,
               directory.resolve(JOURNAL_CORRECTION),
               dimension
            );
         // Validate all decoded payloads before the first world mutation, but
         // release each decoded segment before reading the next one.
         int validatedCorrections = 0;
         for (int segmentIndex = 0; segmentIndex < segments.count(); segmentIndex++) {
            var segment = RecoveryJournalSegments.read(directory, manifest.operationId(), segmentIndex);
            DecodedJournal journal = decode(level, segment.payload());
            // Reject invalid palette references before the first world write.
            for (int index = 0; index < journal.size(); index++) {
               JournalPair pair = journal.pair(index);
               if (corrections != null
                  && validatedCorrections < corrections.size()
                  && corrections.position(validatedCorrections) == pair.after().pos().asLong()) {
                  validatedCorrections++;
               }
            }
         }
         if (corrections != null && validatedCorrections != corrections.size()) {
            throw new IOException("Recovery correction positions are not aligned with the segmented journal");
         }
         int correctionIndex = corrections == null || sealed ? 0 : corrections.size() - 1;
         int restored = 0;
         int preserved = 0;
         for (int segmentOffset = 0; segmentOffset < segments.count(); segmentOffset++) {
            int segmentIndex = sealed ? segmentOffset : segments.count() - 1 - segmentOffset;
            var segment = RecoveryJournalSegments.read(directory, manifest.operationId(), segmentIndex);
            var journal = decode(level, segment.payload());
            for (int cell = 0; cell < journal.size(); cell++) {
               int index = sealed ? cell : journal.size() - 1 - cell;
               JournalPair pair = journal.pair(index);
               if (corrections != null
                  && correctionIndex >= 0
                  && correctionIndex < corrections.size()
                  && corrections.position(correctionIndex) == pair.after().pos().asLong()) {
                  pair = new JournalPair(pair.before(), corrections.snapshot(correctionIndex));
                  correctionIndex += sealed ? 1 : -1;
               }
               var target = sealed ? pair.after() : pair.before();
               var source = sealed ? pair.before() : pair.after();
               StartupRecoveryAction action = startupRecoveryAction(
                  target.matches(level, target.pos()), source.matches(level, source.pos())
               );
               if (action == StartupRecoveryAction.PRESERVE_EXTERNAL) {
                  preserved++;
               } else if (action == StartupRecoveryAction.RESTORE) {
                  if (!target.restore(level, PlacementUpdateMode.CLIENT_ONLY.flags())) {
                     throw new IOException("Could not restore " + target.pos().toShortString());
                  }
                  restored++;
               }
            }
         }
         if (corrections != null && correctionIndex != (sealed ? corrections.size() : -1)) {
            throw new IOException("Recovery correction positions are not aligned with the segmented journal");
         }
         LOGGER.warn("{} {} positions from segmented journal {} (operation={}); preserved {} external changes",
            sealed ? "Replayed" : "Rolled back", restored, directory, manifest.operationId(), preserved);
         return true;
      } catch (IOException | RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("Could not restore sealed segmented journal {}; files remain for retry", directory, exception);
         return false;
      }
   }

   enum StartupRecoveryAction {
      ALREADY_RESTORED,
      RESTORE,
      PRESERVE_EXTERNAL
   }
}
