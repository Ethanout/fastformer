package io.github.fastformer.client.session.receipt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns receipt caching, strict reads, retry boundaries, and file protection for one scope. */
public final class ClientReceiptRepository {
   private final Supplier<Path> defaultFile;
   private Path explicitFile;
   private OperationSubmissionReceiptStore store;
   private ReceiptReadState readState = ReceiptReadState.NOT_LOADED;

   public ClientReceiptRepository(Supplier<Path> defaultFile) {
      this.defaultFile = Objects.requireNonNull(defaultFile, "defaultFile");
   }

   public Path file() {
      return explicitFile == null ? defaultFile.get() : explicitFile;
   }

   /** Selects the file used by every read and write; cached contents retain their ownership. */
   public void useFile(Path file) {
      explicitFile = Objects.requireNonNull(file, "file");
   }

   /** A cached failure is returned without another read or another failure notification. */
   public ReadResult read() {
      if (store != null) return new ReadResult(store, false, null);
      Exception failure = null;
      try {
         store = OperationSubmissionReceiptStore.load(file());
         readState = ReceiptReadState.READY;
      } catch (OperationSubmissionReceiptCodec.VersionMismatchException exception) {
         readState = ReceiptReadState.VERSION_INCOMPATIBLE;
         failure = exception;
         store = OperationSubmissionReceiptStore.unreadable();
      } catch (IOException | RuntimeException exception) {
         readState = ReceiptReadState.RETRYABLE_FAILURE;
         failure = exception;
         store = OperationSubmissionReceiptStore.unreadable();
      }
      return new ReadResult(store, true, failure);
   }

   public ReceiptReadState readState() {
      return readState;
   }

   /** Retries only after a caller supplied an explicit boundary. */
   public boolean retry() {
      if (!readState.mayRetry()) return false;
      readState = ReceiptReadState.NOT_LOADED;
      if (store != null && store.readOnly()) store = null;
      return true;
   }

   public boolean retryTransientFailure() {
      return readState == ReceiptReadState.RETRYABLE_FAILURE && retry();
   }

   public boolean canEvict() {
      return !readState.mayRetry() && (store == null || !store.dirty());
   }

   /** Failed saves keep the cached store dirty and keep unreadable files untouched. */
   public void save() throws IOException {
      read().store().save(file());
      readState = ReceiptReadState.READY;
   }

   public record ReadResult(OperationSubmissionReceiptStore store, boolean newlyRead, Exception failure) {}
}
