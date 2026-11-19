package com.talkbot.plugin;

import com.talkbot.plugin.listener.WhisperListener;
import com.talkbot.plugin.commands.TalkBotCommand;
import com.talkbot.plugin.listener.ChatListener;
import com.talkbot.plugin.manager.BotRegistry;
import com.talkbot.plugin.manager.ChatManager;
import com.talkbot.plugin.manager.DatabaseManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TalkBotPlugin extends JavaPlugin {
    private DatabaseManager database;
    private BotRegistry botRegistry;
    private ChatManager chatManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadDatabase()) {
            getLogger().severe("TalkBot could not start because SQLite initialization failed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        botRegistry = new BotRegistry(this, database);
        chatManager = new ChatManager(this, botRegistry);

        PluginCommand command = getCommand("talkbot");
        if (command == null) {
            getLogger().severe("Command 'talkbot' is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TalkBotCommand executor = new TalkBotCommand(this, botRegistry, chatManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new ChatListener(this, chatManager), this);
        getServer().getPluginManager().registerEvents(
        new WhisperListener(this, botRegistry, chatManager),
        this
);
        getLogger().info("TalkBot 2.0 enabled. Registered bots: " + botRegistry.getRegisteredBots().size());
    }

    public boolean reloadTalkBot() {
        reloadConfig();
        if (!reloadDatabase()) return false;
        botRegistry.reload();
        chatManager.reload();
        return true;
    }

    private boolean reloadDatabase() {
        if (database != null) database.close();
        database = new DatabaseManager(this);
        return database.open();
    }

    @Override
    public void onDisable() {
        if (chatManager != null) chatManager.shutdown();
        if (database != null) database.close();
        getLogger().info("TalkBot 2.0 disabled.");
    }

    public DatabaseManager getDatabase() { return database; }
    public BotRegistry getBotRegistry() { return botRegistry; }
}
