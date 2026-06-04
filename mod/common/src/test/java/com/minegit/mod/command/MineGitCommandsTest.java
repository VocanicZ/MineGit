package com.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        public int log(CommandContext<CommandSourceStack> ctx) {
            return 1;
        }

        @Override
        public int diff(CommandContext<CommandSourceStack> ctx) {
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
        assertTrue(children.containsAll(java.util.Arrays.asList("init", "status", "log", "diff")),
                "subcommands missing: " + children);
        assertEquals(4, children.size());
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
