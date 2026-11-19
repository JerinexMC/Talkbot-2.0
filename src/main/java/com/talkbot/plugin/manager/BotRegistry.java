package com.talkbot.plugin.manager;

import com.talkbot.plugin.TalkBotPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

public final class BotRegistry {
    private final TalkBotPlugin plugin;
    private final DatabaseManager database;
    private final Set<String> registeredBots = new TreeSet<>();

    public BotRegistry(TalkBotPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        reload();
    }

    public synchronized void reload() {
        registeredBots.clear();
        try (PreparedStatement ps = database.connection().prepareStatement("SELECT username FROM registered_bots");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) registeredBots.add(normalize(rs.getString("username")));
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not load registered bots.", ex);
        }
    }

    public synchronized boolean registerBot(String username) {
        if (!validUsername(username)) return false;
        String normalized = normalize(username);
        if (registeredBots.contains(normalized)) return false;
        try (PreparedStatement ps = database.connection().prepareStatement("INSERT INTO registered_bots(username) VALUES(?)")) {
            ps.setString(1, normalized);
            ps.executeUpdate();
            registeredBots.add(normalized);
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not register " + normalized, ex);
            return false;
        }
    }

    public synchronized boolean unregisterBot(String username) {
        String normalized = normalize(username);
        if (!registeredBots.contains(normalized)) return false;
        try (PreparedStatement ps = database.connection().prepareStatement("DELETE FROM registered_bots WHERE username=?")) {
            ps.setString(1, normalized);
            if (ps.executeUpdate() == 0) return false;
            registeredBots.remove(normalized);
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not unregister " + normalized, ex);
            return false;
        }
    }

    public synchronized boolean isBot(String username) { return registeredBots.contains(normalize(username)); }
    public synchronized Set<String> getRegisteredBots() {
        return Collections.unmodifiableSet(new TreeSet<>(registeredBots));
    }

    private static String normalize(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    private static boolean validUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9_]{3,16}");
    }
}
