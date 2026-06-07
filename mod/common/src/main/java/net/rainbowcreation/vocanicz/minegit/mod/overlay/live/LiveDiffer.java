package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/** Pure per-section compare of the live world against the frozen HEAD baseline (Spec SP2 §2c). */
public final class LiveDiffer {

    /** Returns the block changes (live vs frozen HEAD) for every position in the section. */
    public List<BlockChange> diffSection(
            DirtySectionTracker.Section section, HeadBaselineCache cache, LevelAccess level) {
        List<BlockChange> out = new ArrayList<BlockChange>();
        int baseX = section.chunk().getCx() << 4;
        int baseZ = section.chunk().getCz() << 4;
        int baseY = section.sectionY() * 16;

        for (int dy = 0; dy < 16; dy++) {
            int y = baseY + dy;
            for (int dz = 0; dz < 16; dz++) {
                int z = baseZ + dz;
                for (int dx = 0; dx < 16; dx++) {
                    int x = baseX + dx;
                    BlockState head = cache.headAt(section.dimension(), x, y, z);
                    BlockState live = level.getBlock(x, y, z);
                    if (live == null) {
                        live = BlockState.AIR;
                    }
                    boolean headAir = BlockState.AIR.equals(head);
                    boolean liveAir = BlockState.AIR.equals(live);
                    if (headAir && liveAir) {
                        continue;
                    }
                    if (head.equals(live)) {
                        continue;
                    }
                    if (headAir) {
                        out.add(BlockChange.add(x, y, z, live));
                    } else if (liveAir) {
                        out.add(BlockChange.remove(x, y, z, head));
                    } else {
                        out.add(BlockChange.change(x, y, z, head, live));
                    }
                }
            }
        }
        return out;
    }
}
