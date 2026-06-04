package com.minegit.mod;

import com.minegit.mod.gametest.MineGitGameTestLogic;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Fabric GameTest entry (Spec D §6, issue #64) — registered under the {@code fabric-gametest}
 * entrypoint in {@code fabric.mod.json}. Each method is a thin wrapper over the loader-agnostic
 * {@link MineGitGameTestLogic}, so Fabric and NeoForge run the identical place→commit→mutate→checkout
 * loop. Run headlessly via {@code ./gradlew :mod:fabric:runGametest}.
 */
public final class MineGitFabricGameTest {

    @GameTest(maxTicks = 200)
    public void placeCommitMutateCheckoutRevert(GameTestHelper helper) {
        MineGitGameTestLogic.placeCommitMutateCheckoutRevert(helper);
    }

    @GameTest(maxTicks = 200)
    public void noOpCheckoutIsClean(GameTestHelper helper) {
        MineGitGameTestLogic.noOpCheckoutIsClean(helper);
    }
}
