package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class EnvelopeTest {
   private static final Envelope.Format FORMAT = new Envelope.Format(0x46465431, 1, 42);

   @Test
   void roundTripOwnsTheEncodedAndDecodedBytes() throws IOException {
      byte[] source = {1, 2, 3};
      byte[] encoded = Envelope.encode(FORMAT, source);
      source[0] = 9;
      byte[] decoded = Envelope.decode(encoded, FORMAT, 3);
      encoded[Envelope.HEADER_BYTES] = 9;
      assertArrayEquals(new byte[]{1, 2, 3}, decoded);
   }

   @Test
   void crc32cCoversMetadataAndTheExactStoredPayload() {
      byte[] encoded = Envelope.encode(FORMAT, new byte[]{42});
      var crc = new CRC32C();
      crc.update(encoded, 0, 16);
      crc.update(encoded, Envelope.HEADER_BYTES, 1);
      assertEquals(crc.getValue(), ByteBuffer.wrap(encoded).getLong(16));
   }

   @Test
   void rejectsEveryTruncationAndTrailingData() {
      byte[] encoded = Envelope.encode(FORMAT, new byte[]{1, 2, 3});
      for (int length = 0; length < encoded.length; length++) {
         byte[] truncated = Arrays.copyOf(encoded, length);
         assertThrows(IOException.class, () -> Envelope.decode(truncated, FORMAT, 3));
      }
      assertThrows(IOException.class, () -> Envelope.decode(Arrays.copyOf(encoded, encoded.length + 1), FORMAT, 4));
   }

   @Test
   void rejectsEveryCorruptedByteIncludingHeaderMetadata() {
      byte[] encoded = Envelope.encode(FORMAT, new byte[]{42});
      for (int index = 0; index < encoded.length; index++) {
         byte[] corrupt = encoded.clone();
         corrupt[index] ^= 1;
         assertThrows(IOException.class, () -> Envelope.decode(corrupt, FORMAT, 32));
      }
   }

   @Test
   void classifiesOnlyVerifiedUnsupportedVersionsAsFormatMismatches() {
      byte[] encoded = Envelope.encode(new Envelope.Format(FORMAT.magic(), 2, 42), new byte[]{42});
      assertThrows(Envelope.FormatMismatchException.class, () -> Envelope.decode(encoded, FORMAT, 32));
      byte[] corrupt = Envelope.encode(FORMAT, new byte[]{42});
      corrupt[7] ^= 1;
      IOException failure = assertThrows(IOException.class, () -> Envelope.decode(corrupt, FORMAT, 32));
      assertEquals(IOException.class, failure.getClass());
   }

   @Test
   void dataVersionAndFileKindMustBothMatch() {
      byte[] encoded = Envelope.encode(FORMAT, new byte[]{42});
      assertThrows(Envelope.FormatMismatchException.class,
         () -> Envelope.decode(encoded, new Envelope.Format(FORMAT.magic(), 1, 43), 32));
      assertThrows(IOException.class,
         () -> Envelope.decode(encoded, new Envelope.Format(FORMAT.magic() + 1, 1, 42), 32));
   }

   @Test
   void rejectsInvalidLengthsBeforePayloadAllocation() {
      for (int length : new int[]{-1, Integer.MAX_VALUE, Envelope.MAX_PAYLOAD_BYTES + 1}) {
         byte[] encoded = Envelope.encode(FORMAT, new byte[0]);
         ByteBuffer.wrap(encoded).putInt(12, length);
         assertThrows(IOException.class, () -> Envelope.decode(encoded, FORMAT, Long.MAX_VALUE));
      }
   }

   @Test
   void validatesCallerBudgetAndFormatConfiguration() {
      assertThrows(IllegalArgumentException.class, () -> new Envelope.Format(1, 0, 42));
      assertThrows(IllegalArgumentException.class, () -> new Envelope.Format(1, 1, -1));
      assertThrows(IllegalArgumentException.class, () -> Envelope.decode(new byte[0], FORMAT, -1));
      byte[] encoded = Envelope.encode(FORMAT, new byte[]{1, 2, 3});
      assertThrows(IOException.class, () -> Envelope.decode(encoded, FORMAT, 2));
   }
}
