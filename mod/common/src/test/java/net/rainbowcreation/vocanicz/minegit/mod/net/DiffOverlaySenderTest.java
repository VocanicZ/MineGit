package net.rainbowcreation.vocanicz.minegit.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Framing;
import net.rainbowcreation.vocanicz.minegit.protocol.Reassembler;
import org.junit.jupiter.api.Test;

/**
 * The server-side {@code /mg diff} overlay push (issue #78): {@code DiffPayload.encode} →
 * {@code Framing.frame} → per-{@code Frame} {@code toBytes()} → {@code sendTo(player, bytes)},
 * gated on {@code canSend}. The send is driven through a {@link DiffOverlaySender.Sink} seam so this
 * pure half (encode/frame/gate) is unit-testable headless with a recording sink — the
 * {@code @ExpectPlatform} packet transmit is proven separately by the both-loader GameTest.
 *
 * <p>The player argument is never dereferenced by the sender — the recording sink decides capability —
 * so {@code null} stands in for a real {@link ServerPlayer} the dedicated server would supply.
 */
final class DiffOverlaySenderTest {

    /** Records every byte[] handed to {@code sendTo}, gated by a fixed {@code canSend} verdict. */
    private static final class RecordingSink implements DiffOverlaySender.Sink {
        private final boolean capable;
        final List<byte[]> sent = new ArrayList<byte[]>();

        RecordingSink(boolean capable) {
            this.capable = capable;
        }

        @Override
        public boolean canSend(ServerPlayer player) {
            return capable;
        }

        @Override
        public void sendTo(ServerPlayer player, byte[] frameBytes) {
            sent.add(frameBytes);
        }
    }

    private static WorldDiff sampleDiff() {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(3, 70, 5, new BlockState("minecraft:stone")));
        changes.add(BlockChange.remove(7, 12, 9, new BlockState("minecraft:dirt")));
        changes.add(BlockChange.change(1, 64, 1,
                new BlockState("minecraft:dirt"), new BlockState("minecraft:stone")));
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims = new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        return new WorldDiff(dims, 1, 1, 1);
    }

    private static WorldDiff reassemble(List<byte[]> frameBytes) {
        Reassembler r = new Reassembler();
        byte[] full = null;
        for (byte[] fb : frameBytes) {
            Optional<byte[]> done = r.add(Frame.fromBytes(fb));
            if (done.isPresent()) {
                full = done.get();
            }
        }
        return DiffPayload.decode(full);
    }

    @Test
    void capablePlayerGetsFramedPayloadThatReassemblesToSource() {
        WorldDiff source = sampleDiff();
        RecordingSink sink = new RecordingSink(true);

        int frames = DiffOverlaySender.send(null, source, "HEAD", "WORKING", sink);

        assertTrue(frames >= 1, "a capable player must receive at least one frame");
        assertEquals(frames, sink.sent.size(), "every framed packet must be enqueued to the sink");
        assertEquals(source, reassemble(sink.sent),
                "the enqueued frames must reassemble + decode back to the source WorldDiff");
    }

    @Test
    void incapablePlayerIsSilentlySkipped() {
        RecordingSink sink = new RecordingSink(false);

        int frames = DiffOverlaySender.send(null, sampleDiff(), "HEAD", "WORKING", sink);

        assertEquals(0, frames, "a canSend=false player triggers no send");
        assertTrue(sink.sent.isEmpty(), "no packet may be enqueued for an incapable player");
    }

    @Test
    void largeDiffSplitsIntoMultipleFramesThatStillReassemble() {
        // Enough distinct changes to exceed one 30 KB frame, proving the multi-frame split path.
        List<BlockChange> changes = new ArrayList<BlockChange>();
        for (int i = 0; i < 6000; i++) {
            int x = i % 16;
            int z = (i / 16) % 16;
            int y = 64 + i;
            changes.add(BlockChange.add(x, y, z, new BlockState("minecraft:stone_" + i)));
        }
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims = new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        WorldDiff source = new WorldDiff(dims, 6000, 0, 0);

        RecordingSink sink = new RecordingSink(true);
        int frames = DiffOverlaySender.send(null, source, "a", "b", sink);

        assertTrue(frames > 1, "a >30 KB payload must split into multiple frames, was " + frames);
        assertEquals(source, reassemble(sink.sent),
                "the multi-frame payload must still reassemble + decode to the source");
    }
}
