package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

/** Local-position packing and section-Y arithmetic for the client live-diff engine. */
public final class SectionAddr {

    private SectionAddr() {}

    /** Packs a local block position (each component 0..15) into 0..4095. */
    public static int pack(int dx, int dy, int dz) {
        return ((dy & 15) << 8) | ((dz & 15) << 4) | (dx & 15);
    }

    public static int localX(int packed) {
        return packed & 15;
    }

    public static int localY(int packed) {
        return (packed >> 8) & 15;
    }

    public static int localZ(int packed) {
        return (packed >> 4) & 15;
    }

    /** Section index containing this world Y (floor-divide by 16). */
    public static int sectionY(int worldY) {
        return Math.floorDiv(worldY, 16);
    }

    /** Local 0..15 component of a world coordinate (floor-mod by 16). */
    public static int local(int world) {
        return Math.floorMod(world, 16);
    }
}
