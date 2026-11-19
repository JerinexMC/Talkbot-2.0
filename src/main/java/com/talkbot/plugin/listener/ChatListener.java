
package com.talkbot.plugin.listener;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.ChatManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;

@SuppressWarnings("deprecation")
public final class ChatListener implements Listener {
    private final TalkBotPlugin plugin;
    private final ChatManager chatManager;

    public ChatListener(TalkBotPlugin plugin, ChatManager chatManager) {
        this.plugin = plugin;
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();

        if (plugin.getBotRegistry().isBot(sender.getName())) {
            chatManager.handleBotMessage(sender, event.getMessage());
        } else {
            chatManager.handlePlayerMessage(sender, event.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();

        if (plugin.getBotRegistry().isBot(dead.getName())) {
            chatManager.handleBotDeath(dead);
        } else {
            chatManager.handlePlayerDeath(dead);
        }
    }
}
