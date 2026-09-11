# History disk format

`VersionedHistoryEnvelope` is the first layer of the future server-side history store. It is deliberately independent of Minecraft registries and does not run on the placement path.

The envelope is a fixed binary header: magic (`FFH1`), positive format version, payload length, CRC32, then an opaque payload. Readers reject unknown versions, truncation, trailing bytes, oversized payloads, and checksum failures. Writers must write a temporary file and atomically replace the target only after the envelope is complete.

The payload codec for `WorldChangeBatch` must be registry-aware. It should encode dimension key, operation UUID, positions, block/fluid palette entries, and before/after block-entity NBT. Decoding must receive a live registry provider; no client data is trusted as executable world state. A later migration can add a new envelope version without changing the storage ownership rules.
