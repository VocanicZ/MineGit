package com.minegit.plugin.command;

/**
 * Selects the {@link MessageService} that matches the running server (Spec B §5). Adventure is
 * preferred when its serializer + {@code sendMessage(Component)} surface is present (modern Paper);
 * otherwise the universal {@link LegacyMessageService} is used. The choice is made once at enable.
 */
public final class MessageServices {

    private MessageServices() {}

    /** Whether the Adventure component API is on the classpath and usable for messaging. */
    public static boolean isAdventureAvailable() {
        return AdventureMessageService.createOrNull() != null;
    }

    /** The best available renderer: Adventure-backed when present, else legacy. */
    public static MessageService detect() {
        AdventureMessageService adventure = AdventureMessageService.createOrNull();
        return adventure != null ? adventure : new LegacyMessageService();
    }
}
