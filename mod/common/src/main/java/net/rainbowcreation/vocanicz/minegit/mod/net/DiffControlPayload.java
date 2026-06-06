package net.rainbowcreation.vocanicz.minegit.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.vocanicz.minegit.protocol.Protocol;

/**
 * The {@code minegit:diffsub} custom payload (issue #91): an <strong>opaque {@code byte[]}</strong>
 * carrying one {@link net.rainbowcreation.vocanicz.minegit.protocol.DiffControl DiffControl}'s
 * {@code encode()} output (a single control byte) client→server. Mirrors batch 1's server→client
 * {@link DiffRawPayload}, reusing the same raw-byte-payload pattern so the wire form stays uniform.
 *
 * <p>The codec writes/reads the bytes with <em>no</em> length prefix — they are the packet's trailing
 * remainder. The server receiver decodes the control with {@code DiffControl.decode}, which rejects a
 * wrong-length or unknown byte cleanly.
 */
public record DiffControlPayload(byte[] bytes) implements CustomPacketPayload {

    /** The channel id, shared with the engine's wire-protocol constant ({@code minegit:diffsub}). */
    public static final Type<DiffControlPayload> TYPE =
            new Type<>(Identifier.parse(Protocol.DIFF_CONTROL_CHANNEL));

    /**
     * Reads/writes the opaque bytes with no length prefix: encode appends the whole array, decode
     * drains every remaining byte of the payload. Mirrors {@link DiffRawPayload}'s "data is the
     * remainder" layout.
     */
    public static final StreamCodec<FriendlyByteBuf, DiffControlPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DiffControlPayload::write, DiffControlPayload::read);

    private static void write(DiffControlPayload payload, FriendlyByteBuf buf) {
        buf.writeBytes(payload.bytes);
    }

    private static DiffControlPayload read(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new DiffControlPayload(data);
    }

    @Override
    public Type<DiffControlPayload> type() {
        return TYPE;
    }
}
