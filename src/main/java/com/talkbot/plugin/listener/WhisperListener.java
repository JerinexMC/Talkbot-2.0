
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

public final class WhisperListener implements Listener {

    private final TalkBotPlugin plugin;
    private final BotRegistry registry;
    private final ChatManager chatManager;

    public WhisperListener(
            TalkBotPlugin plugin,
            BotRegistry registry,
            ChatManager chatManager
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWhisper(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();

        // Ignore commands sent by TalkBot bots.
        if (registry.isBot(sender.getName())) return;

        String command = event.getMessage().trim();

        if (!command.startsWith("/")) return;

        String[] args = command.substring(1).split("\\s+", 3);

        if (args.length < 3) return;

        String commandName = args[0].toLowerCase(Locale.ROOT);

        // Vanilla private-message commands and common aliases.
        if (commandName.contains(":")) {
            commandName = commandName.substring(commandName.indexOf(':') + 1);
        }

        if (!commandName.equals("w")
                && !commandName.equals("msg")
                && !commandName.equals("tell")
                && !commandName.equals("whisper")
                && !commandName.equals("message")
                && !commandName.equals("pm")) {
            return;
        }

        String targetName = args[1];
        String privateMessage = args[2];

        if (privateMessage.isBlank()) return;

        // Only respond to registered bots that are actually online.
        if (!registry.isBot(targetName)) return;

        Player bot = Bukkit.getPlayerExact(targetName);

        if (bot == null) return;

        chatManager.handleWhisperMessage(
                sender,
                bot.getName(),
                privateMessage
        );
    }
}
