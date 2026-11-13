package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class PayloadOutputStreamTest {
   @Test
   void rejectedBulkWriteCannotPartiallyConsumeOrModifyThePayload() throws IOException {
      var bytes = new ByteArrayOutputStream();
      try (var output = new PayloadOutputStream(bytes, 3)) {
         output.write(1);
         assertThrows(IOException.class, () -> output.write(new byte[]{2, 3, 4}));
         output.write(new byte[]{2, 3});
         output.write(new byte[0]);
         assertThrows(IOException.class, () -> output.write(4));
      }
      assertArrayEquals(new byte[]{1, 2, 3}, bytes.toByteArray());
   }

   @Test
   void invalidBoundsDoNotConsumeTheBudget() throws IOException {
      var bytes = new ByteArrayOutputStream();
      try (var output = new PayloadOutputStream(bytes, 1)) {
         assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[1], 1, 1));
         output.write(42);
      }
      assertArrayEquals(new byte[]{42}, bytes.toByteArray());
   }
}
