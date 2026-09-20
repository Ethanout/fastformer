package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import sun.misc.Unsafe;

/**
 * The reception boundary of a transferred recovery snapshot, on the real server runtime.
 *
 * <p>This case needs a context whose notification path fails. A server shell without a player
 * list gives exactly that failure. The running game test server has already initialized the
 * server classes, so the shell is always available here. A plain unit test JVM cannot build
 * it, which is why this case lives in the game test runtime and fails hard instead of
 * skipping.
 *
 * <p>The queued capture must not outlive the test. Its snapshot is a real pair of equal
 * captures, so the owner resolves it as already restored on the next server tick without a
 * world write.
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecoveryHandoffBoundaryGameTests {
   private static final String BATCH = "recovery_handoff_boundary";

   private RecoveryHandoffBoundaryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 100)
   public static void aFailedNotificationCannotTurnAnAcceptedSnapshotIntoAFailure(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Void> readyForRecovery = new CompletableFuture<>();
      WorldRecoverySnapshot recovery = alreadyRestoredSnapshot(helper, level, readyForRecovery);

      try {
         TaskCancellationResult result = WorldHistoryManager.acceptTransferredRecovery(
            new WorldTaskContext(failingRecipientServer(), owner),
            level.dimension(),
            recovery,
            null,
            () -> {},
            () -> {}
         );

         // The notification throws inside the call. The queue already owns the capture, so
         // the call must still report the accepted result and must not throw.
         helper.assertTrue(
            result == TaskCancellationResult.ROLLBACK_STARTED,
            "a failed notification changed the accepted result to " + result
         );
         helper.assertTrue(
            WorldHistoryManager.recoveryCaptureCountForTest(owner) == 1,
            "the queue did not hold the accepted snapshot exactly once"
         );
      } finally {
         // The capture must not outlive this test, and this runs even when an assertion above
         // fails. Completing the signal lets the owner resolve the capture on the next server
         // tick: the world already matches the before state, so recovery resolves it as
         // already restored without writing a block.
         readyForRecovery.complete(null);
      }

      helper.succeedWhen(() -> helper.assertTrue(
         WorldHistoryManager.recoveryCaptureCountForTest(owner) == 0,
         "waiting for the queued recovery capture to resolve"
      ));
   }

   /**
    * A snapshot whose before and after sides are equal captures of a real block. The live
    * world already matches it, so recovery resolves the capture without a world write.
    */
   private static WorldRecoverySnapshot alreadyRestoredSnapshot(
      GameTestHelper helper, ServerLevel level, CompletableFuture<Void> readyForRecovery
   ) {
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      return new WorldRecoverySnapshot(
         new ArrayDeque<>(List.of(before)), Map.of(pos, after), readyForRecovery
      );
   }

   /** A server shell without a player list, so the notification path throws. */
   private static MinecraftServer failingRecipientServer() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (MinecraftServer)((Unsafe)field.get(null)).allocateInstance(DedicatedServer.class);
      } catch (ReflectiveOperationException | LinkageError error) {
         throw new AssertionError("the game test runtime cannot build the failing notification recipient", error);
      }
   }
}
