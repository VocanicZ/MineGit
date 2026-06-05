package net.rainbowcreation.vocanicz.minegit.mod.overlay;

/**
 * One block-sized translucent box the overlay draws at integer block coordinates, colored by the
 * {@link OverlayColor} legend of the underlying change.
 *
 * <p>Immutable, value-equal. Pure data — no Minecraft imports — so it round-trips through headless
 * tests and the thin renderer reads it without translation.
 */
public final class OverlayBox {

    private final int x;
    private final int y;
    private final int z;
    private final OverlayColor color;

    public OverlayBox(int x, int y, int z, OverlayColor color) {
        if (color == null) {
            throw new NullPointerException("color");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public OverlayColor getColor() {
        return color;
    }

    /**
     * Squared distance from this box's center {@code (x+0.5, y+0.5, z+0.5)} to the camera. Squared
     * to keep the nearest-first sort allocation-free and exact (no {@code sqrt}).
     */
    double distanceSqTo(double camX, double camY, double camZ) {
        double dx = (x + 0.5) - camX;
        double dy = (y + 0.5) - camY;
        double dz = (z + 0.5) - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OverlayBox)) {
            return false;
        }
        OverlayBox that = (OverlayBox) o;
        return x == that.x && y == that.y && z == that.z && color == that.color;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + z;
        h = 31 * h + color.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "OverlayBox(" + color + " @" + x + "," + y + "," + z + ")";
    }
}
