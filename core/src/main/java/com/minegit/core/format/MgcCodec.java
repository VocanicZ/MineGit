package com.minegit.core.format;

import com.minegit.core.model.BlockEntity;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic compact-binary codec for the {@code .mgc} chunk format.
 *
 * <p>The goal is <b>byte-identical output for equal chunks</b>: two {@link NormalizedChunk}s that
 * represent the same world content (even if their section palettes were built in different orders)
 * serialize to exactly the same bytes, so a content-addressed store such as git deduplicates
 * unchanged chunks for free.
 *
 * <p>Determinism is achieved by canonicalizing on write: each section's palette is sorted by
 * {@code (id, props-as-canonical-string)} and the block indices are remapped to the sorted palette;
 * block-state properties are always written in key-sorted order; empty (all-air / {@code null})
 * sections are omitted; and block entities are emitted in {@code (y, z, x)} order.
 *
 * <p>Because the codec canonicalizes, {@link #serialize} is idempotent through a decode
 * ({@code serialize(deserialize(b)) == b}) and {@code deserialize(serialize(c))} reconstructs a
 * chunk equal to {@code c} whenever {@code c} is already in canonical form (the natural form
 * produced by adapters and by this codec's own reader).
 *
 * <p>Batch 1 is uncompressed; deterministic DEFLATE is a later addition. This module performs no
 * Minecraft-specific work and has no Minecraft imports.
 *
 * <h2>Binary layout (big-endian)</h2>
 *
 * <pre>
 * magic "MGC1" (4 bytes) | formatVersion (u8) | cx (svarint) | cz (svarint) | minSection (i8)
 * sectionArrayLen (uvarint) | biomeLen (uvarint) | biomes[]: value (uvarint)...
 * presentSectionCount (uvarint)
 * per present section (ascending array index):
 *   sectionIndex (u8) | paletteLen (uvarint)
 *   palette[]: idLen(uvarint) id(utf8) | propCount(uvarint) [keyLen key valLen val]...  (props key-sorted)
 *   bitsPerIndex (u8) | packed long[] (ceil(4096*bits/64) longs, big-endian longs, LSB-first bit packing)
 * blockEntityCount (uvarint)
 *   [ x(svarint) y(svarint) z(svarint) snbtLen(uvarint) snbt(utf8) ]...   (sorted by (y, z, x))
 * </pre>
 */
public final class MgcCodec {

    private static final byte[] MAGIC = {'M', 'G', 'C', '1'};
    private static final int FORMAT_VERSION = 1;

    private MgcCodec() {}

    /** Serializes a chunk to its canonical {@code .mgc} byte representation. */
    public static byte[] serialize(NormalizedChunk chunk) {
        if (chunk == null) {
            throw new NullPointerException("chunk");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        out.write(MAGIC, 0, MAGIC.length);
        writeU8(out, FORMAT_VERSION);
        writeSVarint(out, chunk.getCx());
        writeSVarint(out, chunk.getCz());
        writeI8(out, chunk.getMinSection());

        NormalizedSection[] sections = chunk.getSections();
        writeUVarint(out, sections.length);

        int[] biomes = chunk.getBiomes();
        writeUVarint(out, biomes.length);
        for (int b : biomes) {
            writeUVarint(out, b);
        }

        int present = 0;
        for (NormalizedSection s : sections) {
            if (s != null) {
                present++;
            }
        }
        writeUVarint(out, present);
        for (int i = 0; i < sections.length; i++) {
            NormalizedSection s = sections[i];
            if (s != null) {
                writeU8(out, i);
                writeSection(out, s);
            }
        }

        List<BlockEntity> bes = new ArrayList<BlockEntity>(chunk.getBlockEntities());
        Collections.sort(bes, BLOCK_ENTITY_ORDER);
        writeUVarint(out, bes.size());
        for (BlockEntity be : bes) {
            writeSVarint(out, be.getX());
            writeSVarint(out, be.getY());
            writeSVarint(out, be.getZ());
            writeUtf8(out, be.getSnbt());
        }

        return out.toByteArray();
    }

    /** Reconstructs a chunk from its {@code .mgc} byte representation. */
    public static NormalizedChunk deserialize(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        ByteReader in = new ByteReader(bytes);
        for (byte expected : MAGIC) {
            if (in.readByte() != expected) {
                throw new IllegalArgumentException("bad magic: not an .mgc blob");
            }
        }
        int version = in.readU8();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported .mgc formatVersion: " + version);
        }
        int cx = in.readSVarint();
        int cz = in.readSVarint();
        int minSection = in.readI8();

        int sectionArrayLen = in.readUVarint();
        NormalizedSection[] sections = new NormalizedSection[sectionArrayLen];

        int biomeLen = in.readUVarint();
        int[] biomes = new int[biomeLen];
        for (int i = 0; i < biomeLen; i++) {
            biomes[i] = in.readUVarint();
        }

        int present = in.readUVarint();
        for (int p = 0; p < present; p++) {
            int sectionIndex = in.readU8();
            sections[sectionIndex] = readSection(in);
        }

        int beCount = in.readUVarint();
        List<BlockEntity> bes = new ArrayList<BlockEntity>(beCount);
        for (int i = 0; i < beCount; i++) {
            int x = in.readSVarint();
            int y = in.readSVarint();
            int z = in.readSVarint();
            String snbt = in.readUtf8();
            bes.add(new BlockEntity(x, y, z, snbt));
        }

        return new NormalizedChunk(cx, cz, minSection, sections, biomes, bes);
    }

    // ---- section (de)serialization ------------------------------------------------------------

    private static void writeSection(ByteArrayOutputStream out, NormalizedSection section) {
        List<BlockState> original = section.getPalette();
        int[] indices = section.getIndices();

        // Canonicalize: sort the palette and build the old->new index remap.
        List<BlockState> sorted = new ArrayList<BlockState>(original);
        Collections.sort(sorted, BLOCK_STATE_ORDER);
        int[] remap = new int[original.size()];
        for (int oldIdx = 0; oldIdx < original.size(); oldIdx++) {
            remap[oldIdx] = indexOf(sorted, original.get(oldIdx));
        }
        int[] canonical = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            canonical[i] = remap[indices[i]];
        }

        writeUVarint(out, sorted.size());
        for (BlockState bs : sorted) {
            writeUtf8(out, bs.getId());
            Map<String, String> props = bs.getProps();
            writeUVarint(out, props.size());
            for (Map.Entry<String, String> e : props.entrySet()) {
                writeUtf8(out, e.getKey());
                writeUtf8(out, e.getValue());
            }
        }

        int bits = bitsPerIndex(sorted.size());
        writeU8(out, bits);
        long[] packed = pack(canonical, bits);
        for (long word : packed) {
            writeLongBe(out, word);
        }
    }

    private static NormalizedSection readSection(ByteReader in) {
        int paletteLen = in.readUVarint();
        List<BlockState> palette = new ArrayList<BlockState>(paletteLen);
        for (int i = 0; i < paletteLen; i++) {
            String id = in.readUtf8();
            int propCount = in.readUVarint();
            if (propCount == 0) {
                palette.add(new BlockState(id));
            } else {
                java.util.Map<String, String> props =
                        new java.util.LinkedHashMap<String, String>(propCount * 2);
                for (int j = 0; j < propCount; j++) {
                    String key = in.readUtf8();
                    String val = in.readUtf8();
                    props.put(key, val);
                }
                palette.add(new BlockState(id, props));
            }
        }
        int bits = in.readU8();
        int numLongs = numLongs(bits);
        long[] packed = new long[numLongs];
        for (int i = 0; i < numLongs; i++) {
            packed[i] = in.readLongBe();
        }
        int[] indices = unpack(packed, bits);
        return new NormalizedSection(palette, indices);
    }

    private static int indexOf(List<BlockState> list, BlockState value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(value)) {
                return i;
            }
        }
        throw new IllegalStateException("palette entry vanished during canonicalization");
    }

    // ---- ordering -----------------------------------------------------------------------------

    private static final Comparator<BlockState> BLOCK_STATE_ORDER =
            new Comparator<BlockState>() {
                @Override
                public int compare(BlockState a, BlockState b) {
                    int byId = a.getId().compareTo(b.getId());
                    if (byId != 0) {
                        return byId;
                    }
                    return canonicalProps(a).compareTo(canonicalProps(b));
                }
            };

    private static final Comparator<BlockEntity> BLOCK_ENTITY_ORDER =
            new Comparator<BlockEntity>() {
                @Override
                public int compare(BlockEntity a, BlockEntity b) {
                    if (a.getY() != b.getY()) {
                        return Integer.compare(a.getY(), b.getY());
                    }
                    if (a.getZ() != b.getZ()) {
                        return Integer.compare(a.getZ(), b.getZ());
                    }
                    if (a.getX() != b.getX()) {
                        return Integer.compare(a.getX(), b.getX());
                    }
                    return a.getSnbt().compareTo(b.getSnbt());
                }
            };

    /** Canonical {@code key=val,key=val} string for a state's (already key-sorted) properties. */
    private static String canonicalProps(BlockState bs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : bs.getProps().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    // ---- bit packing --------------------------------------------------------------------------

    /** Bits needed to address a palette of {@code size} entries (minimum 1). */
    static int bitsPerIndex(int size) {
        if (size <= 1) {
            return 1;
        }
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    private static int numLongs(int bits) {
        return (NormalizedSection.VOLUME * bits + 63) / 64;
    }

    /** Tightly packs {@code VOLUME} indices, {@code bits} each, LSB-first across a {@code long[]}. */
    static long[] pack(int[] indices, int bits) {
        long[] out = new long[numLongs(bits)];
        long mask = (bits == 64) ? -1L : ((1L << bits) - 1);
        for (int i = 0; i < indices.length; i++) {
            long value = indices[i] & mask;
            int bitPos = i * bits;
            int word = bitPos >>> 6;
            int offset = bitPos & 63;
            out[word] |= value << offset;
            int spill = offset + bits - 64;
            if (spill > 0) {
                out[word + 1] |= value >>> (bits - spill);
            }
        }
        return out;
    }

    /** Inverse of {@link #pack}: unpacks {@code VOLUME} indices of {@code bits} each. */
    static int[] unpack(long[] packed, int bits) {
        int[] out = new int[NormalizedSection.VOLUME];
        long mask = (bits == 64) ? -1L : ((1L << bits) - 1);
        for (int i = 0; i < out.length; i++) {
            int bitPos = i * bits;
            int word = bitPos >>> 6;
            int offset = bitPos & 63;
            long value = packed[word] >>> offset;
            int spill = offset + bits - 64;
            if (spill > 0) {
                value |= packed[word + 1] << (bits - spill);
            }
            out[i] = (int) (value & mask);
        }
        return out;
    }

    // ---- primitive writers --------------------------------------------------------------------

    private static void writeU8(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
    }

    private static void writeI8(ByteArrayOutputStream out, int v) {
        if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
            throw new IllegalArgumentException("value out of i8 range: " + v);
        }
        out.write(v & 0xFF);
    }

    private static void writeLongBe(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (v >>> shift) & 0xFF);
        }
    }

    /** Unsigned LEB128 varint. */
    private static void writeUVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }

    /** ZigZag-encoded signed varint. */
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
                throw new IllegalArgumentException("unexpected end of .mgc blob");
            }
            return buf[pos++];
        }

        int readU8() {
            return readByte() & 0xFF;
        }

        int readI8() {
            return readByte();
        }

        long readLongBe() {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (readByte() & 0xFFL);
            }
            return v;
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

        int readSVarint() {
            int u = readUVarint();
            return (u >>> 1) ^ -(u & 1);
        }

        String readUtf8() {
            int len = readUVarint();
            if (pos + len > buf.length) {
                throw new IllegalArgumentException("unexpected end of .mgc blob");
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
