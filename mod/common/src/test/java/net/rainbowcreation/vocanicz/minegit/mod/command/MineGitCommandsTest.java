package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The registered {@code /minegit} tree shape — subcommands present, aliases redirecting, every
 * subcommand gated (issue #60). Execution itself needs a live server, so a no-op runtime stands in.
 */
class MineGitCommandsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A runtime that records nothing and succeeds — registration must not invoke it. */
    private static final MineGitCommands.Runtime NOOP = new MineGitCommands.Runtime() {
        @Override
        public int init(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int status(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int commit(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int log(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int diff(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int checkout(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int rescan(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }
    };

    private CommandDispatcher<CommandSourceStack> registered() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<CommandSourceStack>();
        MineGitCommands.register(dispatcher, NOOP);
        return dispatcher;
    }

    @Test
    void registersPrimaryLiteralWithTheReadSetupSubcommands() {
        CommandNode<CommandSourceStack> root = registered().getRoot().getChild("minegit");
        assertNotNull(root, "/minegit should be registered");
        List<String> children = new ArrayList<String>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            children.add(child.getName());
        }
        assertTrue(children.containsAll(
                        java.util.Arrays.asList("init", "status", "commit", "log", "diff", "checkout", "rescan")),
                "subcommands missing: " + children);
        assertEquals(7, children.size());
    }

    @Test
    void checkoutTakesARequiredRefAndAnOptionalForceFlag() {
        CommandNode<CommandSourceStack> checkout =
                registered().getRoot().getChild("minegit").getChild("checkout");
        assertNotNull(checkout, "/minegit checkout should be registered");
        // Bare `/mg checkout` carries no target, so the literal itself is not executable.
        assertNull(checkout.getCommand(), "bare /mg checkout has no target — not executable");
        // `/mg checkout <ref>` is executable (force defaults off)…
        CommandNode<CommandSourceStack> ref = checkout.getChild("ref");
        assertNotNull(ref, "/mg checkout should take a ref argument");
        assertNotNull(ref.getCommand(), "/mg checkout <ref> should execute");
        // …and `/mg checkout <ref> --force` nests the force flag and is also executable.
        CommandNode<CommandSourceStack> force = ref.getChild("--force");
        assertNotNull(force, "/mg checkout <ref> should accept --force");
        assertNotNull(force.getCommand(), "/mg checkout <ref> --force should execute");
    }

    @Test
    void refOfReadsTheCheckoutTarget() {
        assertEquals("main", MineGitCommands.refOf(parseCheckout("main")));
        assertEquals("abc123", MineGitCommands.refOf(parseCheckout("abc123 --force")));
    }

    @Test
    void isForceIsTrueOnlyWhenTheFlagIsPresent() {
        assertFalse(MineGitCommands.isForce(parseCheckout("abc123")));
        assertTrue(MineGitCommands.isForce(parseCheckout("abc123 --force")));
    }

    /**
     * A requirement-free mirror of the checkout argument shape, so {@link MineGitCommands#refOf} and
     * {@link MineGitCommands#isForce} can be exercised on a real Brigadier context without a
     * permission-bearing {@code CommandSourceStack}. The {@code ref} arg name and {@code --force}
     * literal match {@link MineGitCommands}.
     */
    private static CommandContext<CommandSourceStack> parseCheckout(String tail) {
        CommandDispatcher<CommandSourceStack> dispatcher =
                new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<CommandSourceStack>literal("t")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("ref",
                                com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(c -> 1)
                        .then(com.mojang.brigadier.builder.LiteralArgumentBuilder
                                .<CommandSourceStack>literal("--force")
                                .executes(c -> 1))));
        String input = "t " + tail;
        return dispatcher.parse(input, (CommandSourceStack) null).getContext().build(input);
    }

    @Test
    void commitTakesAnOptionalDashMMessageArgument() {
        CommandNode<CommandSourceStack> commit =
                registered().getRoot().getChild("minegit").getChild("commit");
        assertNotNull(commit, "/minegit commit should be registered");
        // Bare `/mg commit` is runnable (default message)…
        assertNotNull(commit.getCommand(), "bare commit should execute");
        // …and `/mg commit -m <message>` carries a greedy message argument.
        CommandNode<CommandSourceStack> dashM = commit.getChild("-m");
        assertNotNull(dashM, "commit should accept -m");
        CommandNode<CommandSourceStack> message = dashM.getChild("message");
        assertNotNull(message, "-m should be followed by a message argument");
        assertNotNull(message.getCommand(), "the message form should execute");
    }

    /**
     * A requirement-free mirror of the commit argument shape, so {@link MineGitCommands#messageOf}
     * can be exercised on a real Brigadier context without a permission-bearing {@code
     * CommandSourceStack} (parsing the gated tree needs a live source). The greedy {@code message}
     * arg name matches {@link MineGitCommands#MESSAGE_ARG}.
     */
    private static CommandContext<CommandSourceStack> parseMessageArg(String tail) {
        CommandDispatcher<CommandSourceStack> dispatcher =
                new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(com.mojang.brigadier.builder.LiteralArgumentBuilder
                .<CommandSourceStack>literal("t")
                .executes(c -> 1)
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument("message",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(c -> 1)));
        String input = tail.isEmpty() ? "t" : "t " + tail;
        return dispatcher.parse(input, (CommandSourceStack) null).getContext().build(input);
    }

    @Test
    void messageOfReadsTheGreedyDashMArgument() {
        // The greedy argument captures the whole tail verbatim.
        assertEquals("build a tall tower",
                MineGitCommands.messageOf(parseMessageArg("build a tall tower")));
    }

    @Test
    void messageOfFallsBackToTheDefaultWhenNoArgumentIsPresent() {
        assertEquals(MineGitCommands.DEFAULT_COMMIT_MESSAGE,
                MineGitCommands.messageOf(parseMessageArg("")));
    }

    @Test
    void diffAcceptsAnOptionalRefPairForRefVsRef() {
        CommandNode<CommandSourceStack> diff =
                registered().getRoot().getChild("minegit").getChild("diff");
        assertNotNull(diff, "/minegit diff should be registered");
        // Bare /mg diff runs (working-vs-HEAD), so the literal itself is executable...
        assertNotNull(diff.getCommand(), "bare /mg diff should be executable (working-vs-HEAD)");
        // ...and /mg diff <refA> <refB> nests two string arguments for ref-vs-ref.
        CommandNode<CommandSourceStack> refA = diff.getChild("refA");
        assertNotNull(refA, "/mg diff should take a first ref argument");
        CommandNode<CommandSourceStack> refB = refA.getChild("refB");
        assertNotNull(refB, "/mg diff <refA> should take a second ref argument");
        assertNotNull(refB.getCommand(), "/mg diff <refA> <refB> should be executable");
    }

    @Test
    void aliasesRedirectToThePrimaryLiteral() {
        CommandDispatcher<CommandSourceStack> dispatcher = registered();
        CommandNode<CommandSourceStack> primary = dispatcher.getRoot().getChild("minegit");
        for (String alias : java.util.Arrays.asList("mg", "git")) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(alias);
            assertNotNull(node, "alias /" + alias + " should be registered");
            assertSame(primary, node.getRedirect(), "/" + alias + " should redirect to /minegit");
        }
    }

    @Test
    void everySubcommandCarriesAPermissionRequirementSeam() {
        CommandNode<CommandSourceStack> root = registered().getRoot().getChild("minegit");
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            assertNotNull(child.getRequirement(), child.getName() + " should be gated");
        }
    }
}
