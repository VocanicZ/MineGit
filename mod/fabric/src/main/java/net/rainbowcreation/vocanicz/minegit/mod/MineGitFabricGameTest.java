package net.rainbowcreation.vocanicz.minegit.mod;

import net.rainbowcreation.vocanicz.minegit.mod.command.LiveOverlayRetiredLoopGameTest;
import net.rainbowcreation.vocanicz.minegit.mod.gametest.MineGitGameTestLogic;
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

    @GameTest(maxTicks = 200)
    public void incrementalDirtyCommitReverts(GameTestHelper helper) {
        MineGitGameTestLogic.incrementalDirtyCommitReverts(helper);
    }

    @GameTest(maxTicks = 200)
    public void mixinFiresOnRealBlockChange(GameTestHelper helper) {
        MineGitGameTestLogic.mixinFiresOnRealBlockChange(helper);
    }

    @GameTest(maxTicks = 200)
    public void diffPayloadRoundTripsToClientHandler(GameTestHelper helper) {
        MineGitGameTestLogic.diffPayloadRoundTripsToClientHandler(helper);
    }

    @GameTest(maxTicks = 200)
    public void diffServerSendFramesReachCapturedSink(GameTestHelper helper) {
        MineGitGameTestLogic.diffServerSendFramesReachCapturedSink(helper);
    }

    @GameTest(maxTicks = 200)
    public void controlPacketRoundTripsToServerHandler(GameTestHelper helper) {
        MineGitGameTestLogic.controlPacketRoundTripsToServerHandler(helper);
    }

    @GameTest(maxTicks = 200)
    public void liveSubscriptionPushesOnChangeThenStopsOnUnsubscribe(GameTestHelper helper) {
        MineGitGameTestLogic.liveSubscriptionPushesOnChangeThenStopsOnUnsubscribe(helper);
    }

    @GameTest(maxTicks = 200)
    public void liveOverlayTicksPushNothingOnRealServer(GameTestHelper helper) {
        LiveOverlayRetiredLoopGameTest.ticksPushNothingOnRealServer(helper);
    }
}
