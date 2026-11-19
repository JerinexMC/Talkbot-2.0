package com.talkbot.plugin.manager;

import com.talkbot.plugin.TalkBotPlugin;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public final class DatabaseManager {
    private final TalkBotPlugin plugin;
    private Connection connection;

    public DatabaseManager(TalkBotPlugin plugin) { this.plugin = plugin; }

    public synchronized boolean open() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new SQLException("Could not create plugin data folder.");
            }
            String filename = plugin.getConfig().getString("database.filename", "talkbot.db");
            File file = new File(plugin.getDataFolder(), filename);
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS registered_bots (username TEXT PRIMARY KEY NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS chat_memory (id INTEGER PRIMARY KEY AUTOINCREMENT, bot_username TEXT NOT NULL, speaker TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL)");
            }
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize SQLite database.", ex);
            close();
            return false;
        }
    }

    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) throw new SQLException("Database is not available.");
        return connection;
    }

    public synchronized void close() {
        if (connection == null) return;
        try { connection.close(); }
        catch (SQLException ex) { plugin.getLogger().log(Level.WARNING, "Error closing SQLite.", ex); }
        finally { connection = null; }
    }
}
