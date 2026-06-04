package com.minegit.plugin.command;

import java.lang.reflect.Method;
import org.bukkit.command.CommandSender;

/**
 * Renders MineGit lines as Adventure {@code Component}s on servers that ship the Adventure API
 * (modern Paper). Adventure is <strong>not</strong> on the 1.8.8 compile classpath, so every call is
 * reflective: the legacy section string is up-converted via
 * {@code LegacyComponentSerializer.legacySection().deserialize(String)} and handed to
 * {@code CommandSender.sendMessage(Component)}. Any reflective failure degrades to
 * {@code sendMessage(String)}, so a partial Adventure shim never silences output.
 *
 * <p>Constructed only when {@link MessageServices#isAdventureAvailable()} is true; the handles are
 * resolved once and cached. The reflection itself is validated in-game / on a modern test server
 * (the unit tests cover selection and the legacy renderer).
 */
final class AdventureMessageService implements MessageService {

    private final Method deserialize;
    private final Object serializer;
    private final Method sendComponent;

    private AdventureMessageService(Method deserialize, Object serializer, Method sendComponent) {
        this.deserialize = deserialize;
        this.serializer = serializer;
        this.sendComponent = sendComponent;
    }

    /** Resolves the Adventure handles, or returns {@code null} if they are not all present. */
    static AdventureMessageService createOrNull() {
        try {
            Class<?> serializerClass =
                    Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            Object serializer = serializerClass.getMethod("legacySection").invoke(null);
            Method deserialize = serializerClass.getMethod("deserialize", String.class);
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Method sendComponent = CommandSender.class.getMethod("sendMessage", componentClass);
            return new AdventureMessageService(deserialize, serializer, sendComponent);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    @Override
    public void send(CommandSender sender, String legacyText) {
        try {
            Object component = deserialize.invoke(serializer, legacyText);
            sendComponent.invoke(sender, component);
        } catch (ReflectiveOperationException | LinkageError e) {
            sender.sendMessage(legacyText);
        }
    }
}
