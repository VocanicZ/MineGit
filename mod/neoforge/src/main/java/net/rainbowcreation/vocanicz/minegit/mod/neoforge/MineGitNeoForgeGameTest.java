package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.gametest.MineGitGameTestLogic;
import com.mojang.serialization.MapCodec;
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

    /** Hooks the registration onto the mod event bus; the event only fires when GameTest is enabled. */
    public static void register(IEventBus modEventBus) {
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
     * {@code TEST_FUNCTION} registry. Its {@link #codec()} is never exercised (the instance is never
     * serialized on a dedicated GameTest server), so it borrows the function-instance codec.
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
