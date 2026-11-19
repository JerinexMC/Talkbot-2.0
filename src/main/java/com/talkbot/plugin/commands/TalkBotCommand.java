package com.talkbot.plugin.commands;

import com.talkbot.plugin.TalkBotPlugin;
import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public final class TalkBotCommand implements CommandExecutor, TabCompleter {
    private final TalkBotPlugin plugin;
    private final BotRegistry registry;
    private final ChatManager chatManager;

    public TalkBotCommand(TalkBotPlugin plugin, BotRegistry registry, ChatManager chatManager) {
        this.plugin = plugin;
        this.registry = registry;
        this.chatManager = chatManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("talkbot.admin")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase()) {
            case "register" -> {
                if (args.length < 2) return usage(sender);
                if (registry.registerBot(args[1])) sender.sendMessage(Component.text("Registered " + args[1], NamedTextColor.GREEN));
                else sender.sendMessage(Component.text("Invalid username, already registered, or database error.", NamedTextColor.RED));
            }
            case "unregister" -> {
                if (args.length < 2) return usage(sender);
                if (registry.unregisterBot(args[1])) sender.sendMessage(Component.text("Unregistered " + args[1], NamedTextColor.GREEN));
                else sender.sendMessage(Component.text("Bot was not registered.", NamedTextColor.RED));
            }
            case "list" -> {
                sender.sendMessage(Component.text("Registered TalkBot accounts:", NamedTextColor.GOLD));
                if (registry.getRegisteredBots().isEmpty()) sender.sendMessage(Component.text("None registered.", NamedTextColor.GRAY));
                for (String name : registry.getRegisteredBots()) {
                    boolean online = Bukkit.getPlayerExact(name) != null;
                    sender.sendMessage(Component.text("- " + name + (online ? " (online)" : " (offline)"),
                            online ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                }
            }
            case "reload" -> {
                if (plugin.reloadTalkBot()) sender.sendMessage(Component.text("TalkBot config and registry reloaded.", NamedTextColor.GREEN));
                else sender.sendMessage(Component.text("Reload failed. Check console for database errors.", NamedTextColor.RED));
            }
            case "status" -> {
                sender.sendMessage(Component.text("Registered bots: " + registry.getRegisteredBots().size(), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("AI enabled in config: " + plugin.getConfig().getBoolean("ai.enabled"), NamedTextColor.AQUA));
                sender.sendMessage(Component.text("AI key configured: " +
                        (!plugin.getConfig().getString("ai.api-key", "").equals("YOUR_API_KEY_HERE")
                                || (System.getenv("TALK_BOT_API_KEY") != null && !System.getenv("TALK_BOT_API_KEY").isBlank())),
                        NamedTextColor.AQUA));
            }
            case "test" -> handleTest(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) { usage(sender); return; }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage(Component.text("No online Bukkit Player found for " + args[1], NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Online Bukkit Player found: " + player.getName(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Java class: " + player.getClass().getName(), NamedTextColor.GRAY));
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            try {
                player.chat("TalkBot diagnostic message");
                sender.sendMessage(Component.text("Player#chat invoked. Verify whether the message appeared in global chat.", NamedTextColor.YELLOW));
            } catch (Throwable ex) {
                sender.sendMessage(Component.text("Player#chat failed: " + ex.getMessage(), NamedTextColor.RED));
            }
        } else {
            sender.sendMessage(Component.text("To send a public test message: /talkbot test " + args[1] + " confirm", NamedTextColor.YELLOW));
        }
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /talkbot <register|unregister|list|reload|test|status>", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                     @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("register", "unregister", "list", "reload", "test", "status");
        if (args.length == 2 && (args[0].equalsIgnoreCase("unregister") || args[0].equalsIgnoreCase("test")))
            return new ArrayList<>(registry.getRegisteredBots());
        return List.of();
    }
}
