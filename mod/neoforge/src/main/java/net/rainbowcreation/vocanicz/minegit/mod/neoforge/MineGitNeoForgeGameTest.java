package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.command.LiveOverlayRetiredLoopGameTest;
import net.rainbowcreation.vocanicz.minegit.mod.gametest.MineGitGameTestLogic;
import com.mojang.serialization.MapCodec;
import java.util.Arrays;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * NeoForge GameTest registration (Spec D §6, issue #64). The 1.21.11 GameTest framework is
 * data-driven (tests live in the {@code minecraft:test_instance} registry) and NeoForge dropped its
 * old {@code @GameTestHolder} annotations, so tests are registered in code via
 * {@link RegisterGameTestsEvent}. Each registered {@link GameTestInstance} is a thin wrapper over the
 * loader-agnostic {@link MineGitGameTestLogic}, so NeoForge runs the same place→commit→mutate→checkout
 * loop Fabric does. Run headlessly via {@code ./gradlew :mod:neoforge:runGameTestServer}.
 */
public final class MineGitNeoForgeGameTest {

    /** Empty 8x8x8 test arena shipped at {@code data/minegit/structure/empty.nbt}. */
    private static final Identifier STRUCTURE =
            Identifier.fromNamespaceAndPath(MineGitInfo.MOD_ID, "empty");

    private static final int MAX_TICKS = 200;

    private MineGitNeoForgeGameTest() {
    }

    /**
     * Hooks the registration onto the mod event bus — but only when this mod's namespace is in
     * {@code neoforge.enabledGameTestNamespaces} (set solely by the {@code gameTestServer} run).
     * {@link RegisterGameTestsEvent} fires in any dev run, including {@code runClient}, and our
     * {@link CodeTest} instances are code-backed (their body is an unserializable lambda). Without
     * this gate they would be injected into the {@code minecraft:test_instance} registry, which the
     * integrated server network-syncs on every world-join — encoding them throws
     * {@code ClassCastException} (see {@link CodeTest#codec()}). Gating keeps them out of all
     * interactive (and production) registries; only the headless GameTest server ever registers them,
     * mirroring Fabric, whose {@code @GameTest} entrypoint loads solely under its gametest run.
     */
    public static void register(IEventBus modEventBus) {
        String enabledNamespaces = System.getProperty("neoforge.enabledGameTestNamespaces", "");
        if (!Arrays.asList(enabledNamespaces.split(",")).contains(MineGitInfo.MOD_ID)) {
            return;
        }
        modEventBus.addListener(RegisterGameTestsEvent.class, MineGitNeoForgeGameTest::onRegister);
    }

    private static void onRegister(RegisterGameTestsEvent event) {
        // An empty (no-op) environment — the tests own all their world setup themselves.
        Holder<TestEnvironmentDefinition> env =
                event.registerEnvironment(Identifier.fromNamespaceAndPath(MineGitInfo.MOD_ID, "env"));
        registerTest(event, env, "place_commit_mutate_checkout_revert",
                MineGitGameTestLogic::placeCommitMutateCheckoutRevert);
        registerTest(event, env, "no_op_checkout_is_clean",
                MineGitGameTestLogic::noOpCheckoutIsClean);
        registerTest(event, env, "incremental_dirty_commit_reverts",
                MineGitGameTestLogic::incrementalDirtyCommitReverts);
        registerTest(event, env, "mixin_fires_on_real_block_change",
                MineGitGameTestLogic::mixinFiresOnRealBlockChange);
        registerTest(event, env, "diff_payload_round_trips_to_client_handler",
                MineGitGameTestLogic::diffPayloadRoundTripsToClientHandler);
        registerTest(event, env, "diff_server_send_frames_reach_captured_sink",
                MineGitGameTestLogic::diffServerSendFramesReachCapturedSink);
        registerTest(event, env, "control_packet_round_trips_to_server_handler",
                MineGitGameTestLogic::controlPacketRoundTripsToServerHandler);
        registerTest(event, env, "live_subscription_pushes_on_change_then_stops_on_unsubscribe",
                MineGitGameTestLogic::liveSubscriptionPushesOnChangeThenStopsOnUnsubscribe);
        registerTest(event, env, "live_overlay_ticks_push_nothing_on_real_server",
                LiveOverlayRetiredLoopGameTest::ticksPushNothingOnRealServer);
    }

    private static void registerTest(
            RegisterGameTestsEvent event,
            Holder<TestEnvironmentDefinition> env,
            String name,
            Consumer<GameTestHelper> body) {
        TestData<Holder<TestEnvironmentDefinition>> data =
                new TestData<>(env, STRUCTURE, MAX_TICKS, 0, true, Rotation.NONE);
        event.registerTest(Identifier.fromNamespaceAndPath(MineGitInfo.MOD_ID, name),
                new CodeTest(data, body));
    }

    /**
     * A code-backed {@link GameTestInstance}: it is registered directly into the (still-writable)
     * test registry, so unlike {@link FunctionGameTestInstance} it needs no entry in the frozen
     * {@code TEST_FUNCTION} registry. Its {@link #codec()} borrows the function-instance codec, which
     * would {@code ClassCastException} if ever invoked (this is not a {@code FunctionGameTestInstance}).
     * That is safe only because {@link #register(IEventBus)} gates registration to the headless
     * GameTest server, which never network-syncs the {@code test_instance} registry — so the codec is
     * never exercised. Do not lift that gate without giving this type a real, self-encoding codec.
     */
    private static final class CodeTest extends GameTestInstance {
        private final Consumer<GameTestHelper> body;

        CodeTest(TestData<Holder<TestEnvironmentDefinition>> data, Consumer<GameTestHelper> body) {
            super(data);
            this.body = body;
        }

        @Override
        public void run(GameTestHelper helper) {
            body.accept(helper);
        }

        @Override
        public MapCodec<? extends GameTestInstance> codec() {
            return FunctionGameTestInstance.CODEC;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal("MineGit GameTest");
        }
    }
}
