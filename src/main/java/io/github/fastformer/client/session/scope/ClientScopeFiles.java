package io.github.fastformer.client.session.scope;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.neoforged.fml.loading.FMLPaths;

/** Stable file locations of current-format data; live connection generations are excluded. */
public final class ClientScopeFiles {
   private ClientScopeFiles() {}

   public static Path draft(ClientScopeId id) {
      return file("session-drafts", id);
   }

   public static Path receipts(ClientScopeId id) {
      return file("session-receipts", id);
   }

   private static Path file(String directory, ClientScopeId id) {
      String identity = id.connection() + "\n" + id.dimension() + "\n" + id.playerId();
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
         return FMLPaths.CONFIGDIR.get().resolve("fastformer").resolve(directory)
            .resolve(HexFormat.of().formatHex(digest) + ".nbt.gz");
      } catch (NoSuchAlgorithmException exception) {
         throw new IllegalStateException("SHA-256 is not available", exception);
      }
   }
}
