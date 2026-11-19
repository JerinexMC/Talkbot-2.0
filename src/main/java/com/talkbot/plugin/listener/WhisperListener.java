
package com.talkbot.plugin.listener;

import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class WhisperListener implements Listener {
    private final BotRegistry registry;
    private final ChatManager chatManager;

    public WhisperListener(
            com.talkbot.plugin.TalkBotPlugin plugin,
            BotRegistry registry,
            ChatManager chatManager
    ) {
        this.registry = registry;
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWhisper(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();

        if (registry.isBot(sender.getName())) return;

        String[] args = event.getMessage()
                .trim()
                .substring(1)
                .split("\\s+", 3);

        if (args.length < 3) return;

        String command = args[0].toLowerCase(Locale.ROOT);

        if (command.contains(":")) {
            command = command.substring(command.indexOf(':') + 1);
        }

        if (!command.equals("w")
                && !command.equals("msg")
                && !command.equals("tell")
                && !command.equals("whisper")
                && !command.equals("message")
                && !command.equals("pm")) {
            return;
        }

        String targetName = args[1];
        String message = args[2];

        if (!registry.isBot(targetName) || message.isBlank()) return;

        Player bot = Bukkit.getPlayerExact(targetName);
        if (bot == null) return;

        chatManager.handleWhisperMessage(
                sender, bot.getName(), message
        );
    }
}
