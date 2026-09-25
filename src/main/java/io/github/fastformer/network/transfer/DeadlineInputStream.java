package io.github.fastformer.network.transfer;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;

/** Cooperatively limits compressed input work without interrupting the server thread. */
public final class DeadlineInputStream extends FilterInputStream {
   private final long deadline;
   public DeadlineInputStream(InputStream input, long budgetNanos) {
      super(input);
      deadline = System.nanoTime() + budgetNanos;
   }
   private void checkDeadline() throws IOException {
      if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0)
         throw new IOException("Workspace decode deadline exceeded");
   }
   @Override public int read() throws IOException { checkDeadline(); return in.read(); }
   @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      checkDeadline(); return in.read(bytes, offset, length);
   }
}
