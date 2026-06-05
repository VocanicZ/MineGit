package net.rainbowcreation.vocanicz.minegit.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.rainbowcreation.vocanicz.minegit.protocol.Protocol;

/**
 * The {@code minegit:diff} custom payload (issue #77): an <strong>opaque {@code byte[]}</strong>, not a
 * typed mod struct. One payload carries one {@link net.rainbowcreation.vocanicz.minegit.protocol.Frame
 * Frame}'s {@code toBytes()} output verbatim.
 *
 * <p>The codec writes/reads the bytes with <em>no</em> length prefix — they are the packet's trailing
 * remainder, exactly like {@code Frame}'s own {@code data} field. This is deliberate: a future Spigot
 * plugin emitting raw plugin-message bytes on {@code minegit:diff} produces the identical wire form, so
 * the same client receiver decodes both without caring which server sent it.
 */
public record DiffRawPayload(byte[] bytes) implements CustomPacketPayload {

    /** The channel id, shared with the engine's wire-protocol constant ({@code minegit:diff}). */
    public static final Type<DiffRawPayload> TYPE =
            new Type<>(Identifier.parse(Protocol.DIFF_CHANNEL));

    /**
     * Reads/writes the opaque bytes with no length prefix: encode appends the whole array, decode
     * drains every remaining byte of the payload. Mirrors {@code Frame}'s "data is the remainder"
     * layout so a raw Spigot send round-trips identically.
     */
    public static final StreamCodec<FriendlyByteBuf, DiffRawPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DiffRawPayload::write, DiffRawPayload::read);

    private static void write(DiffRawPayload payload, FriendlyByteBuf buf) {
        buf.writeBytes(payload.bytes);
    }

    private static DiffRawPayload read(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new DiffRawPayload(data);
    }

    @Override
    public Type<DiffRawPayload> type() {
        return TYPE;
    }
}
