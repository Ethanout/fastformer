package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VersionedHistoryEnvelopeTest {
   @Test
   void roundTripOwnsItsPayload() throws IOException {
      byte[] source = {1, 2, 3};
      byte[] encoded = VersionedHistoryEnvelope.encode(1, source);
      source[0] = 9;
      var decoded = VersionedHistoryEnvelope.decode(encoded, 1);
      encoded[20] = 9;
      decoded.payload()[0] = 9;
      assertArrayEquals(new byte[]{1, 2, 3}, decoded.payload());
      assertEquals(1, decoded.version());
   }

   @Test
   void rejectsEveryTruncationAndTrailingData() {
      byte[] encoded = VersionedHistoryEnvelope.encode(1, new byte[]{1, 2, 3});
      for (int length = 0; length < encoded.length; length++) {
         byte[] truncated = Arrays.copyOf(encoded, length);
         assertThrows(IOException.class, () -> VersionedHistoryEnvelope.decode(truncated, 1));
      }
      byte[] extended = Arrays.copyOf(encoded, encoded.length + 1);
      assertThrows(IOException.class, () -> VersionedHistoryEnvelope.decode(extended, 1));
   }

   @Test
   void rejectsCorruptionAndUnsupportedVersion() {
      byte[] encoded = VersionedHistoryEnvelope.encode(1, new byte[]{42});
      assertThrows(IOException.class, () -> VersionedHistoryEnvelope.decode(encoded, 2));
      for (int index : new int[]{0, 12, 20}) {
         byte[] corrupt = encoded.clone();
         corrupt[index] ^= 1;
         assertThrows(IOException.class, () -> VersionedHistoryEnvelope.decode(corrupt, 1));
      }
   }

   @Test
   void rejectsInvalidLengthsBeforeAllocation() {
      for (int length : new int[]{-1, Integer.MAX_VALUE, 256 * 1024 * 1024 + 1}) {
         byte[] encoded = VersionedHistoryEnvelope.encode(1, new byte[0]);
         ByteBuffer.wrap(encoded).putInt(8, length);
         assertThrows(IOException.class, () -> VersionedHistoryEnvelope.decode(encoded, 1));
      }
   }
}
