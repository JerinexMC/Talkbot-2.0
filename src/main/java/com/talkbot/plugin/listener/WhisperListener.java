
package com.talkbot.plugin.listener;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

public final class WhisperListener implements Listener {

    private static final Set<String> WHISPER_COMMANDS = Set.of(
            "w",
            "msg",
            "tell",
            "whisper",
            "message",
            "pm",
            "m"
    );

    private final BotRegistry registry;
    private final ChatManager chatManager;

    public WhisperListener(
            TalkBotPlugin plugin,
            BotRegistry registry,
            ChatManager chatManager
    ) {
        this.registry = registry;
        this.chatManager = chatManager;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onWhisper(PlayerCommandPreprocessEvent event) {

        Player sender = event.getPlayer();

        // Bots should not process their own outgoing commands.
        if (registry.isBot(sender.getName())) {
            return;
        }

        String commandLine = event.getMessage();

        if (commandLine == null || !commandLine.startsWith("/")) {
            return;
        }

        String content = commandLine.substring(1).trim();

        if (content.isEmpty()) {
            return;
        }

        int firstSpace = content.indexOf(' ');

        if (firstSpace == -1) {
            return;
        }

        String command = content.substring(0, firstSpace)
                .toLowerCase(Locale.ROOT);

        // Support namespaced commands such as /minecraft:tell.
        int namespace = command.lastIndexOf(':');

        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }

        if (!WHISPER_COMMANDS.contains(command)) {
            return;
        }

        String remaining = content.substring(firstSpace + 1).trim();

        if (remaining.isEmpty()) {
            return;
        }

        int secondSpace = remaining.indexOf(' ');

        if (secondSpace == -1) {
            return;
        }

        String targetName = remaining.substring(0, secondSpace);
        String message = remaining.substring(secondSpace + 1).trim();

        if (message.isEmpty()) {
            return;
        }

        // Only handle messages sent to registered TalkBot accounts.
        if (!registry.isBot(targetName)) {
            return;
        }

        Player bot = Bukkit.getPlayerExact(targetName);

        if (bot == null || !bot.isOnline()) {
            return;
        }

        // Pass the message to the private conversation handler.
        // Do not broadcast the message publicly.
        chatManager.handleWhisperMessage(
                sender,
                bot.getName(),
                message
        );
    }
}
