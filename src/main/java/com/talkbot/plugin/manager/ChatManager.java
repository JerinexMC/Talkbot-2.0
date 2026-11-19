package com.talkbot.plugin.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.talkbot.plugin.TalkBotPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class ChatManager {
    private final TalkBotPlugin plugin;
    private final BotRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Long> lastBotReply = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> memory = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private volatile int minDelay;
    private volatile int maxDelay;
    private volatile double randomChance;
    private volatile double botChance;
    private volatile long cooldownMillis;
    private volatile String apiUrl, apiKey, model, defaultPersonality;
    private volatile int timeoutSeconds, maxTokens;
    private volatile double temperature;
    private volatile boolean enabled;
    private volatile BukkitTask cleanupTask;

    public ChatManager(TalkBotPlugin plugin, BotRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        reload();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long cutoff = System.currentTimeMillis() - 30 * 60_000L;
            lastBotReply.entrySet().removeIf(e -> e.getValue() < cutoff);
        }, 20L * 60, 20L * 60);
    }

    public void reload() {
        var c = plugin.getConfig();
        minDelay = Math.max(0, c.getInt("chat.min-reply-delay-ticks", 25));
        maxDelay = Math.max(minDelay, c.getInt("chat.max-reply-delay-ticks", 75));
        randomChance = clamp(c.getDouble("chat.random-reply-chance", 0.08));
        botChance = clamp(c.getDouble("chat.bot-to-bot-chance", 0.12));
        cooldownMillis = Math.max(0, c.getLong("chat.per-bot-cooldown-seconds", 25)) * 1000L;
        enabled = c.getBoolean("ai.enabled", false);
        apiUrl = c.getString("ai.api-url", "");
        apiKey = System.getenv().getOrDefault("TALK_BOT_API_KEY", c.getString("ai.api-key", ""));
        model = c.getString("ai.model", "");
        timeoutSeconds = Math.max(5, c.getInt("ai.timeout-seconds", 35));
        maxTokens = Math.max(30, c.getInt("ai.max-tokens", 100));
        temperature = Math.max(0.0, Math.min(2.0, c.getDouble("ai.temperature", 0.9)));
        defaultPersonality = c.getString("personality.default", "Talk casually like a normal Minecraft player. No emojis.");
    }

    public void handlePlayerMessage(Player sender, String message) {
        if (shuttingDown.get() || !enabled || !configured()) return;
        boolean addressed = containsAny(message, registry.getRegisteredBots());
        for (String botName : registry.getRegisteredBots()) {
            if (botName.equalsIgnoreCase(sender.getName())) continue;
            if (Bukkit.getPlayerExact(botName) == null) continue;
            boolean mentioned = message.toLowerCase(Locale.ROOT).contains(botName.toLowerCase(Locale.ROOT));
            if (!mentioned && ThreadLocalRandom.current().nextDouble() > randomChance) continue;
            requestReply(botName, sender.getName(), message, mentioned, false);
        }
    }

    public void handleBotMessage(Player bot, String message) {
        if (shuttingDown.get() || !enabled || !configured()) return;
        if (ThreadLocalRandom.current().nextDouble() > botChance) return;
        for (String other : registry.getRegisteredBots()) {
            if (other.equalsIgnoreCase(bot.getName()) || Bukkit.getPlayerExact(other) == null) continue;
            requestReply(other, bot.getName(), message, true, true);
            break;
        }
    }

    private void requestReply(String botName, String speaker, String message, boolean addressed, boolean botToBot) {
        long now = System.currentTimeMillis();
        Long previous = lastBotReply.get(botName);
        if (previous != null && now - previous < cooldownMillis) return;
        lastBotReply.put(botName, now);

        String personality = plugin.getConfig().getString("personality.bots." + botName, defaultPersonality);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("temperature", temperature);
        payload.addProperty("max_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", personality + " Reply in one short natural chat message. No emojis. Don't use markdown. Do not claim to know game state you were not told. You are playing as Minecraft username " + botName + ".");
        messages.add(system);

        Deque<String> history = memory.computeIfAbsent(botName, k -> new ArrayDeque<>());
        synchronized (history) {
            for (String item : history) {
                JsonObject h = new JsonObject();
                h.addProperty("role", item.startsWith("BOT:") ? "assistant" : "user");
                h.addProperty("content", item.substring(item.indexOf(':') + 1).trim());
                messages.add(h);
            }
        }
        JsonObject input = new JsonObject();
        input.addProperty("role", "user");
        input.addProperty("content", (botToBot ? "Another bot" : "Player") + " " + speaker + " said: " + message + (addressed ? " (They are talking to you.)" : ""));
        messages.add(input);
        payload.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_API_KEY_HERE")) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw new IllegalStateException("AI endpoint returned HTTP " + response.statusCode() + ": " + response.body());
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                })
                .thenAccept(reply -> scheduleBotMessage(botName, speaker, message, reply))
                .exceptionally(error -> {
                    plugin.getLogger().warning("AI request for " + botName + " failed: " + error.getMessage());
                    lastBotReply.remove(botName, now);
                    return null;
                });
    }

    private void scheduleBotMessage(String botName, String speaker, String sourceMessage, String reply) {
        if (shuttingDown.get()) return;
        String clean = sanitize(reply);
        if (clean.isBlank()) return;
        int maxLength = Math.max(40, plugin.getConfig().getInt("chat.max-message-length", 180));
        if (clean.length() > maxLength) clean = clean.substring(0, maxLength).trim();
        final String finalReply = clean;
        long delay = minDelay == maxDelay ? minDelay : ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player bot = Bukkit.getPlayerExact(botName);
            if (bot == null || !registry.isBot(botName) || shuttingDown.get()) return;
            Deque<String> history = memory.computeIfAbsent(botName, k -> new ArrayDeque<>());
            synchronized (history) {
                history.addLast("USER: " + speaker + ": " + sourceMessage);
                history.addLast("BOT: " + finalReply);
                while (history.size() > 16) history.removeFirst();
            }
            // This is a real Player#chat attempt. Whether PvpBot exposes its fake player
            // as a Bukkit Player is determined by the diagnostic command on the live server.
            bot.chat(finalReply);
        }, delay);
    }

    private boolean configured() {
        return apiUrl != null && (apiUrl.startsWith("https://") || apiUrl.startsWith("http://"))
                && model != null && !model.isBlank();
    }
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    private static boolean containsAny(String text, Iterable<String> names) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : names) if (lower.contains(name.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\r\\n\\t]+", " ").replaceAll("§[0-9A-FK-ORa-fk-or]", "").trim();
    }
    public void shutdown() {
        shuttingDown.set(true);
        if (cleanupTask != null) cleanupTask.cancel();
    }
}
