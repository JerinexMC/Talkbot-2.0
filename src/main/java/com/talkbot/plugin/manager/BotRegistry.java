
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

    private static final Set<String> STYLES = Set.of(
            "toxic", "chill", "funny", "silly", "cute",
            "girl", "boy", "tomboy", "yn", "brainrot",
            "weird", "dry", "admin-professional", "admin-yn"
    );

    public BotRegistry(TalkBotPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        reload();
    }

    public synchronized void reload() {
        registeredBots.clear();

        try (PreparedStatement ps = database.connection()
                .prepareStatement("SELECT username FROM registered_bots");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                registeredBots.add(normalize(rs.getString("username")));
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.SEVERE, "Could not load registered bots.", ex
            );
        }
    }

    public synchronized boolean registerBot(String username) {
        if (!validUsername(username)) return false;

        String normalized = normalize(username);
        if (registeredBots.contains(normalized)) return false;

        try (PreparedStatement ps = database.connection().prepareStatement(
                "INSERT INTO registered_bots(username) VALUES(?)")) {

            ps.setString(1, normalized);
            ps.executeUpdate();
            registeredBots.add(normalized);
            return true;

        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not register " + normalized, ex
            );
            return false;
        }
    }

    public synchronized boolean unregisterBot(String username) {
        String normalized = normalize(username);
        if (!registeredBots.contains(normalized)) return false;

        try (PreparedStatement ps = database.connection().prepareStatement(
                "DELETE FROM registered_bots WHERE username=?")) {

            ps.setString(1, normalized);

            if (ps.executeUpdate() == 0) return false;

            registeredBots.remove(normalized);

            try (PreparedStatement style = database.connection()
                    .prepareStatement("DELETE FROM bot_styles WHERE username=?")) {
                style.setString(1, normalized);
                style.executeUpdate();
            }

            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not unregister " + normalized, ex
            );
            return false;
        }
    }

    public synchronized boolean setStyle(String username, String style) {
        String bot = normalize(username);
        String selected = normalize(style);

        if (!registeredBots.contains(bot) || !STYLES.contains(selected)) {
            return false;
        }

        try (PreparedStatement ps = database.connection().prepareStatement("""
                INSERT INTO bot_styles(username, style) VALUES(?, ?)
                ON CONFLICT(username) DO UPDATE SET style=excluded.style
                """)) {

            ps.setString(1, bot);
            ps.setString(2, selected);
            ps.executeUpdate();
            return true;

        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not save bot style.", ex
            );
            return false;
        }
    }

    public synchronized String getStyle(String username) {
        String bot = normalize(username);

        try (PreparedStatement ps = database.connection().prepareStatement(
                "SELECT style FROM bot_styles WHERE username=?")) {

            ps.setString(1, bot);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("style");
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not read bot style.", ex
            );
        }

        return "chill";
    }

    public boolean isValidStyle(String style) {
        return STYLES.contains(normalize(style));
    }

    public Set<String> getStyles() {
        return STYLES;
    }

    public synchronized boolean isBot(String username) {
        return registeredBots.contains(normalize(username));
    }

    public synchronized Set<String> getRegisteredBots() {
        return Collections.unmodifiableSet(
                new TreeSet<>(registeredBots)
        );
    }

    private static String normalize(String username) {
        return username == null
                ? ""
                : username.toLowerCase(Locale.ROOT);
    }

    private static boolean validUsername(String username) {
        return username != null
                && username.matches("[A-Za-z0-9_]{3,16}");
    }
}
