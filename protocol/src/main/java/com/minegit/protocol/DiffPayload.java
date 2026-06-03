package com.minegit.protocol;

import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Palette-compressed wire codec for a {@link WorldDiff} streamed over {@link Protocol#DIFF_CHANNEL}.
 *
 * <p>The payload deduplicates every distinct {@link BlockState} into a single shared palette and
 * lets each {@link BlockChange} reference its old/new states by palette index, so a diff that
 * touches thousands of {@code minecraft:stone} blocks pays for that state's id/props bytes exactly
 * once. Everything is VarInt-framed.
 *
 * <p>The codec is deterministic — {@code encode} of two {@linkplain WorldDiff#equals equal} diffs
 * (with the same refs) yields byte-identical output — and round-trips losslessly:
 * {@code decode(encode(d, from, to)).equals(d)}. The block-level {@code added/removed/changed}
 * aggregate counts and the input ordering of dimensions, chunks and changes are all preserved.
 *
 * <p>This module is platform-agnostic: it has <b>no Minecraft imports</b>.
 *
 * <h2>Binary layout (VarInt-framed)</h2>
 *
 * <pre>
 * magic "MGDP" (4 bytes) | formatVersion (u8)
 * fromRef (utf8) | toRef (utf8)
 * added (uvarint) | removed (uvarint) | changed (uvarint)
 * paletteLen (uvarint)
 *   palette[]: idLen(uvarint) id(utf8) | propCount(uvarint) [keyLen key valLen val]...
 * dimensionCount (uvarint)
 * per dimension (input order):
 *   dimId (utf8) | chunkCount (uvarint)
 *   per chunk (input order):
 *     cx (svarint) | cz (svarint) | changeCount (uvarint)
 *     per change (input order):
 *       packedLocalPos (uvarint = (zigzag(y) &lt;&lt; 8) | (localX &lt;&lt; 4) | localZ)
 *       kind (u8: 0=ADD, 1=REMOVE, 2=CHANGE)
 *       oldIdx (uvarint, palette index + 1; 0 = none)
 *       newIdx (uvarint, palette index + 1; 0 = none)
 * </pre>
 */
public final class DiffPayload {

    private static final byte[] MAGIC = {'M', 'G', 'D', 'P'};
    private static final int FORMAT_VERSION = 1;

    private static final int KIND_ADD = 0;
    private static final int KIND_REMOVE = 1;
    private static final int KIND_CHANGE = 2;

    private DiffPayload() {}

    /** Serializes {@code diff} (tagged with {@code fromRef}/{@code toRef}) to its wire bytes. */
    public static byte[] encode(WorldDiff diff, String fromRef, String toRef) {
        if (diff == null) {
            throw new NullPointerException("diff");
        }
        if (fromRef == null) {
            throw new NullPointerException("fromRef");
        }
        if (toRef == null) {
            throw new NullPointerException("toRef");
        }

        // Pass 1: build the shared palette in first-seen order (deterministic for equal diffs).
        List<BlockState> palette = new ArrayList<BlockState>();
        Map<BlockState, Integer> index = new HashMap<BlockState, Integer>();
        for (Map.Entry<DimensionId, List<ChunkDiff>> dim : diff.getDimensions().entrySet()) {
            for (ChunkDiff cd : dim.getValue()) {
                for (BlockChange ch : cd.getChanges()) {
                    intern(palette, index, ch.getOldState());
                    intern(palette, index, ch.getNewState());
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.write(MAGIC, 0, MAGIC.length);
        writeU8(out, FORMAT_VERSION);
        writeUtf8(out, fromRef);
        writeUtf8(out, toRef);
        writeUVarint(out, diff.getAdded());
        writeUVarint(out, diff.getRemoved());
        writeUVarint(out, diff.getChanged());

        writeUVarint(out, palette.size());
        for (BlockState bs : palette) {
            writeUtf8(out, bs.getId());
            Map<String, String> props = bs.getProps();
            writeUVarint(out, props.size());
            for (Map.Entry<String, String> e : props.entrySet()) {
                writeUtf8(out, e.getKey());
                writeUtf8(out, e.getValue());
            }
        }

        Map<DimensionId, List<ChunkDiff>> dims = diff.getDimensions();
        writeUVarint(out, dims.size());
        for (Map.Entry<DimensionId, List<ChunkDiff>> dim : dims.entrySet()) {
            writeUtf8(out, dim.getKey().getId());
            List<ChunkDiff> chunks = dim.getValue();
            writeUVarint(out, chunks.size());
            for (ChunkDiff cd : chunks) {
                ChunkPos pos = cd.getPos();
                writeSVarint(out, pos.getCx());
                writeSVarint(out, pos.getCz());
                List<BlockChange> changes = cd.getChanges();
                writeUVarint(out, changes.size());
                for (BlockChange ch : changes) {
                    int localX = ch.getX() - pos.getCx() * 16;
                    int localZ = ch.getZ() - pos.getCz() * 16;
                    long packed = (zigzag(ch.getY()) << 8)
                            | ((localX & 0xF) << 4) | (localZ & 0xF);
                    writeUVarintLong(out, packed);
                    writeU8(out, kindCode(ch.getKind()));
                    writeUVarint(out, idxPlusOne(index, ch.getOldState()));
                    writeUVarint(out, idxPlusOne(index, ch.getNewState()));
                }
            }
        }

        return out.toByteArray();
    }

    /** Reconstructs the {@link WorldDiff} from a payload produced by {@link #encode}. */
    public static WorldDiff decode(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        ByteReader in = new ByteReader(payload);
        readMagic(in);
        int version = in.readU8();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported DiffPayload formatVersion: " + version);
        }
        in.readUtf8(); // fromRef — header metadata, not part of WorldDiff
        in.readUtf8(); // toRef
        int added = in.readUVarint();
        int removed = in.readUVarint();
        int changed = in.readUVarint();

        int paletteLen = in.readUVarint();
        List<BlockState> palette = new ArrayList<BlockState>(paletteLen);
        for (int i = 0; i < paletteLen; i++) {
            String id = in.readUtf8();
            int propCount = in.readUVarint();
            if (propCount == 0) {
                palette.add(new BlockState(id));
            } else {
                Map<String, String> props = new LinkedHashMap<String, String>(propCount * 2);
                for (int j = 0; j < propCount; j++) {
                    String key = in.readUtf8();
                    String val = in.readUtf8();
                    props.put(key, val);
                }
                palette.add(new BlockState(id, props));
            }
        }

        int dimCount = in.readUVarint();
        Map<DimensionId, List<ChunkDiff>> dims =
                new LinkedHashMap<DimensionId, List<ChunkDiff>>(dimCount * 2);
        for (int d = 0; d < dimCount; d++) {
            DimensionId dim = new DimensionId(in.readUtf8());
            int chunkCount = in.readUVarint();
            List<ChunkDiff> chunks = new ArrayList<ChunkDiff>(chunkCount);
            for (int c = 0; c < chunkCount; c++) {
                int cx = in.readSVarint();
                int cz = in.readSVarint();
                int changeCount = in.readUVarint();
                List<BlockChange> changes = new ArrayList<BlockChange>(changeCount);
                for (int k = 0; k < changeCount; k++) {
                    long packed = in.readUVarintLong();
                    int localZ = (int) (packed & 0xF);
                    int localX = (int) ((packed >>> 4) & 0xF);
                    int y = unzigzag(packed >>> 8);
                    int x = cx * 16 + localX;
                    int z = cz * 16 + localZ;
                    int kind = in.readU8();
                    BlockState oldState = lookup(palette, in.readUVarint());
                    BlockState newState = lookup(palette, in.readUVarint());
                    changes.add(rebuild(kind, x, y, z, oldState, newState));
                }
                chunks.add(new ChunkDiff(new ChunkPos(cx, cz), changes));
            }
            dims.put(dim, chunks);
        }

        return new WorldDiff(dims, added, removed, changed);
    }

    /** Reads the {@code fromRef} header tag from a payload without decoding the whole diff. */
    public static String readFromRef(byte[] payload) {
        ByteReader in = new ByteReader(payload);
        readMagic(in);
        in.readU8();
        return in.readUtf8();
    }

    /** Reads the {@code toRef} header tag from a payload without decoding the whole diff. */
    public static String readToRef(byte[] payload) {
        ByteReader in = new ByteReader(payload);
        readMagic(in);
        in.readU8();
        in.readUtf8();
        return in.readUtf8();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void readMagic(ByteReader in) {
        for (byte expected : MAGIC) {
            if (in.readByte() != expected) {
                throw new IllegalArgumentException("bad magic: not a DiffPayload blob");
            }
        }
    }

    private static void intern(
            List<BlockState> palette, Map<BlockState, Integer> index, BlockState bs) {
        if (bs == null || index.containsKey(bs)) {
            return;
        }
        index.put(bs, palette.size());
        palette.add(bs);
    }

    private static int idxPlusOne(Map<BlockState, Integer> index, BlockState bs) {
        if (bs == null) {
            return 0;
        }
        return index.get(bs) + 1;
    }

    private static BlockState lookup(List<BlockState> palette, int idxPlusOne) {
        if (idxPlusOne == 0) {
            return null;
        }
        return palette.get(idxPlusOne - 1);
    }

    private static int kindCode(BlockChange.Kind kind) {
        switch (kind) {
            case ADD:
                return KIND_ADD;
            case REMOVE:
                return KIND_REMOVE;
            case CHANGE:
                return KIND_CHANGE;
            default:
                throw new IllegalStateException("unknown kind: " + kind);
        }
    }

    private static BlockChange rebuild(
            int kind, int x, int y, int z, BlockState oldState, BlockState newState) {
        switch (kind) {
            case KIND_ADD:
                return BlockChange.add(x, y, z, newState);
            case KIND_REMOVE:
                return BlockChange.remove(x, y, z, oldState);
            case KIND_CHANGE:
                return BlockChange.change(x, y, z, oldState, newState);
            default:
                throw new IllegalArgumentException("unknown kind code: " + kind);
        }
    }

    private static long zigzag(int value) {
        return ((long) value << 1) ^ (value >> 31);
    }

    private static int unzigzag(long u) {
        return (int) ((u >>> 1) ^ -(u & 1));
    }

    // ---- primitive writers --------------------------------------------------------------------

    private static void writeU8(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
    }

    /** Unsigned LEB128 varint (32-bit). */
    private static void writeUVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }

    /** Unsigned LEB128 varint (64-bit) — used for the packed local position. */
    private static void writeUVarintLong(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write((int) (v & 0x7F));
    }

    /** ZigZag-encoded signed varint (32-bit). */
    private static void writeSVarint(ByteArrayOutputStream out, int value) {
        writeUVarint(out, (value << 1) ^ (value >> 31));
    }

    private static void writeUtf8(ByteArrayOutputStream out, String s) {
        byte[] b = utf8(s);
        writeUVarint(out, b.length);
        out.write(b, 0, b.length);
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    // ---- reader -------------------------------------------------------------------------------

    private static final class ByteReader {
        private final byte[] buf;
        private int pos;

        ByteReader(byte[] buf) {
            this.buf = buf;
        }

        byte readByte() {
            if (pos >= buf.length) {
                throw new IllegalArgumentException("unexpected end of DiffPayload blob");
            }
            return buf[pos++];
        }

        int readU8() {
            return readByte() & 0xFF;
        }

        int readUVarint() {
            int result = 0;
            int shift = 0;
            while (true) {
                int b = readU8();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift >= 35) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        long readUVarintLong() {
            long result = 0;
            int shift = 0;
            while (true) {
                int b = readU8();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift >= 70) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        int readSVarint() {
            int u = readUVarint();
            return (u >>> 1) ^ -(u & 1);
        }

        String readUtf8() {
            int len = readUVarint();
            if (pos + len > buf.length) {
                throw new IllegalArgumentException("unexpected end of DiffPayload blob");
            }
            try {
                String s = new String(buf, pos, len, "UTF-8");
                pos += len;
                return s;
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 unavailable", e);
            }
        }
    }
}
