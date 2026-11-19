
package com.talkbot.plugin.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.talkbot.plugin.TalkBotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatManager {
    private final TalkBotPlugin plugin;
    private final BotRegistry registry;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, Long> lastBotReply = new ConcurrentHashMap<>();
    private final Map<String, Long> ignoredUntil = new ConcurrentHashMap<>();

    private final Map<String, Deque<String>> publicMemory =
            new ConcurrentHashMap<>();

    private final Map<String, Deque<String>> privateMemory =
            new ConcurrentHashMap<>();

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile int minDelay;
    private volatile int maxDelay;
    private volatile double randomChance;
    private volatile double botChance;
    private volatile long cooldownMillis;
    private volatile String apiUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile String defaultPersonality;
    private volatile int timeoutSeconds;
    private volatile int maxTokens;
    private volatile double temperature;
    private volatile boolean enabled;
    private volatile BukkitTask cleanupTask;

    public ChatManager(TalkBotPlugin plugin, BotRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;

        reload();

        cleanupTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    long cutoff = System.currentTimeMillis() - 30 * 60_000L;
                    lastBotReply.entrySet().removeIf(
                            e -> e.getValue() < cutoff
                    );
                    ignoredUntil.entrySet().removeIf(
                            e -> e.getValue() < System.currentTimeMillis()
                    );
                },
                20L * 60,
                20L * 60
        );
    }

    public void reload() {
        var c = plugin.getConfig();

        minDelay = Math.max(
                0, c.getInt("chat.min-reply-delay-ticks", 20)
        );

        maxDelay = Math.max(
                minDelay, c.getInt("chat.max-reply-delay-ticks", 60)
        );

        randomChance = clamp(
                c.getDouble("chat.random-reply-chance", 0.20)
        );

        botChance = clamp(
                c.getDouble("chat.bot-to-bot-chance", 0.12)
        );

        cooldownMillis = Math.max(
                0, c.getLong("chat.per-bot-cooldown-seconds", 15)
        ) * 1000L;

        enabled = c.getBoolean("ai.enabled", false);
        apiUrl = c.getString("ai.api-url", "");

        apiKey = System.getenv().getOrDefault(
                "TALK_BOT_API_KEY",
                c.getString("ai.api-key", "")
        );

        model = c.getString("ai.model", "");
        timeoutSeconds = Math.max(
                5, c.getInt("ai.timeout-seconds", 35)
        );

        maxTokens = Math.max(
                30, c.getInt("ai.max-tokens", 120)
        );

        temperature = Math.max(
                0.0,
                Math.min(2.0, c.getDouble("ai.temperature", 0.9))
        );

        defaultPersonality = c.getString(
                "personality.default",
                "You are an ordinary Minecraft player. Be natural and concise. No emojis."
        );
    }

    // ---------------------------------------------------------
    // PUBLIC CHAT
    // ---------------------------------------------------------

    public void handlePlayerMessage(Player sender, String message) {
        if (shuttingDown.get()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sender.isOnline()) return;

            handleModeration(sender, message);

            if (!enabled || !configured()) return;

            List<String> bots = new ArrayList<>(
                    registry.getRegisteredBots()
            );

            for (String botName : bots) {
                if (botName.equalsIgnoreCase(sender.getName())) continue;

                Player bot = Bukkit.getPlayerExact(botName);
                if (bot == null) continue;

                boolean mentioned = isMentioned(message, botName);

                if (isIgnored(botName)) continue;

                // Deterministic game actions first.
                if (handleGameActions(bot, sender, message, mentioned)) {
                    continue;
                }

                if (!mentioned
                        && ThreadLocalRandom.current().nextDouble()
                        > randomChance) {
                    continue;
                }

                requestReply(
                        botName,
                        sender.getName(),
                        message,
                        mentioned,
                        false
                );
            }
        });
    }

    public void handleBotMessage(Player bot, String message) {
        if (shuttingDown.get() || !enabled || !configured()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!bot.isOnline()) return;

            if (ThreadLocalRandom.current().nextDouble() > botChance) {
                return;
            }

            for (String other : registry.getRegisteredBots()) {
                if (other.equalsIgnoreCase(bot.getName())) continue;
                if (isIgnored(other)) continue;

                Player target = Bukkit.getPlayerExact(other);
                if (target == null) continue;

                requestReply(
                        other, bot.getName(), message, true, true
                );
                break;
            }
        });
    }

    // ---------------------------------------------------------
    // PRIVATE WHISPERS
    // ---------------------------------------------------------

    public void handleWhisperMessage(
            Player sender,
            String botName,
            String message
    ) {
        if (shuttingDown.get() || !enabled || !configured()) return;

        if (!registry.isBot(botName)) return;

        Player bot = Bukkit.getPlayerExact(botName);
        if (bot == null) return;

        requestReply(
                botName,
                sender.getName(),
                message,
                true,
                false,
                true
        );
    }

    // ---------------------------------------------------------
    // GAME ACTIONS
    // ---------------------------------------------------------

    private boolean handleGameActions(
            Player bot,
            Player sender,
            String message,
            boolean mentioned
    ) {
        String lower = message.toLowerCase(Locale.ROOT);

        boolean tpaRequest =
                lower.contains("tpa to me")
                || lower.contains("teleport to me")
                || lower.contains("come to me")
                || lower.contains("come here");

        if (tpaRequest) {
            boolean accept = mentioned
                    || ThreadLocalRandom.current().nextDouble() < 0.50;

            if (accept) {
                bot.performCommand("tpa " + sender.getName());
            }

            return true;
        }

        boolean spawnRequest =
                lower.contains("go to spawn")
                || lower.contains("come to spawn")
                || lower.contains("return to spawn");

        if (mentioned && spawnRequest) {
            bot.performCommand("spawn");
            return true;
        }

        boolean coordinateRequest =
                lower.contains("your coordinates")
                || lower.contains("your coords")
                || lower.contains("where are you")
                || lower.contains("what are your coords");

        if (mentioned && coordinateRequest) {
            Location loc = bot.getLocation();

            bot.chat(
                    "I'm at " + loc.getBlockX()
                            + ", " + loc.getBlockY()
                            + ", " + loc.getBlockZ()
            );

            return true;
        }

        boolean woodRequest =
                lower.contains("wood")
                || lower.contains("logs")
                || lower.contains("planks");

        boolean giveRequest =
                lower.contains("give me")
                || lower.contains("can i have")
                || lower.contains("need some")
                || lower.contains("got any")
                || lower.contains("can you give");

        if (mentioned && woodRequest && giveRequest) {
            return transferWood(bot, sender);
        }

        return false;
    }

    private boolean transferWood(Player bot, Player receiver) {
        if (!bot.isOnline() || !receiver.isOnline()) return true;

        var inventory = bot.getInventory();

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);

            if (item == null || item.getType().isAir()) continue;

            String name = item.getType().name();

            boolean isWood =
                    name.endsWith("_LOG")
                    || name.endsWith("_WOOD")
                    || name.endsWith("_PLANKS")
                    || name.equals("BAMBOO_BLOCK")
                    || name.equals("STRIPPED_BAMBOO_BLOCK");

            if (!isWood) continue;

            ItemStack offered = item.clone();
            Map<Integer, ItemStack> leftover =
                    receiver.getInventory().addItem(offered);

            if (leftover.isEmpty()) {
                inventory.setItem(slot, null);
                bot.chat("here you go");
            } else {
                int transferred = offered.getAmount();

                for (ItemStack remaining : leftover.values()) {
                    transferred -= remaining.getAmount();
                }

                if (transferred > 0) {
                    item.setAmount(item.getAmount() - transferred);
                    bot.chat("gave you what i could");
                } else {
                    bot.chat("your inventory is full");
                }
            }

            return true;
        }

        bot.chat("i don't have any wood on me rn");
        return true;
    }

    // ---------------------------------------------------------
    // NAME RECOGNITION
    // ---------------------------------------------------------

    private boolean isMentioned(String message, String botName) {
        String lower = message.toLowerCase(Locale.ROOT);
        String name = botName.toLowerCase(Locale.ROOT);

        // Full username always counts.
        if (containsWord(lower, name)) return true;

        // Shortened first 3-4 characters.
        for (int length = 3; length <= Math.min(4, name.length()); length++) {
            String prefix = name.substring(0, length);

            if (!containsWord(lower, prefix)) continue;

            int matches = 0;

            for (String registered : registry.getRegisteredBots()) {
                if (registered.toLowerCase(Locale.ROOT)
                        .startsWith(prefix)) {
                    matches++;
                }
            }

            // Only resolve a shortened name if it is unambiguous.
            if (matches == 1) return true;
        }

        return false;
    }

    private boolean containsWord(String text, String word) {
        return java.util.regex.Pattern.compile(
                "(?i)(?<![a-z0-9_])"
                        + java.util.regex.Pattern.quote(word)
                        + "(?![a-z0-9_])"
        ).matcher(text).find();
    }

    // ---------------------------------------------------------
    // ADMIN MODERATION
    // ---------------------------------------------------------

    private void handleModeration(Player sender, String message) {
        if (!containsBlockedLanguage(message)) return;

        for (String botName : registry.getRegisteredBots()) {
            String style = registry.getStyle(botName);

            if (!style.startsWith("admin-")) continue;

            Player bot = Bukkit.getPlayerExact(botName);
            if (bot == null) continue;

            String key = "moderation:" + sender.getUniqueId();
            long now = System.currentTimeMillis();

            Long previous = lastBotReply.get(key);

            // Avoid multiple admin bots warning for the same incident.
            if (previous != null && now - previous < 15_000L) return;

            lastBotReply.put(key, now);

            if (style.equals("admin-professional")) {
                bot.chat(
                        sender.getName()
                                + " yo don't say that you can get banned"
                );
            } else {
                bot.chat(
                        sender.getName()
                                + " aye don't talk nun of that here gang"
                );
            }

            return;
        }
    }

    private boolean containsBlockedLanguage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        String[] blocked = {
                "rape",
                "raped",
                "grape",
                "nigga",
                "nigger",
                "nig",
                "nigg"
        };

        for (String word : blocked) {
            if (normalized.contains(word)) return true;
        }

        return false;
    }

    // ---------------------------------------------------------
    // DEATH REACTIONS
    // ---------------------------------------------------------

    public void handlePlayerDeath(Player dead) {
        if (!enabled || !configured()) return;

        for (String botName : registry.getRegisteredBots()) {
            if (isIgnored(botName)) continue;

            Player bot = Bukkit.getPlayerExact(botName);
            if (bot == null) continue;

            if (bot.getWorld() != dead.getWorld()) continue;

            if (bot.getLocation().distanceSquared(dead.getLocation()) > 1600) {
                continue;
            }

            if (ThreadLocalRandom.current().nextDouble() > 0.35) {
                continue;
            }

            requestReply(
                    botName,
                    dead.getName(),
                    dead.getName() + " just died. React naturally.",
                    false,
                    false
            );
        }
    }

    public void handleBotDeath(Player dead) {
        // Bot death reactions can be added without pretending
        // that the bot has information about its killer.
    }

    // ---------------------------------------------------------
    // AI REQUESTS
    // ---------------------------------------------------------

    private void requestReply(
            String botName,
            String speaker,
            String message,
            boolean addressed,
            boolean botToBot
    ) {
        requestReply(
                botName, speaker, message, addressed, botToBot, false
        );
    }

    private void requestReply(
            String botName,
            String speaker,
            String message,
            boolean addressed,
            boolean botToBot,
            boolean whisper
    ) {
        if (shuttingDown.get() || !configured()) return;

        long now = System.currentTimeMillis();

        Long previous = lastBotReply.get(botName);

        if (previous != null && now - previous < cooldownMillis) {
            return;
        }

        lastBotReply.put(botName, now);

        String style = registry.getStyle(botName);
        String personality = stylePrompt(style);

        String memoryKey = whisper
                ? botName.toLowerCase(Locale.ROOT)
                    + ":" + speaker.toLowerCase(Locale.ROOT)
                : botName.toLowerCase(Locale.ROOT);

        Map<String, Deque<String>> memoryMap =
                whisper ? privateMemory : publicMemory;

        Deque<String> history = memoryMap.computeIfAbsent(
                memoryKey,
                k -> new ArrayDeque<>()
        );

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("temperature", temperature);
        payload.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");

        String systemPrompt =
                defaultPersonality
                + "\nYou are Minecraft player " + botName + "."
                + "\nYour current style is " + style + "."
                + "\n" + personality
                + "\nTalk like a real player, not an AI assistant."
                + "\nYou can discuss Minecraft, anime, television, "
                + "sports, school, games, and normal real-world topics."
                + "\nNever claim to know game state you were not given."
                + "\nNever invent inventory, coordinates, items, or actions."
                + "\nDo not use emojis."
                + "\nKeep messages natural and usually short."
                + "\nDo not repeat the same response constantly."
                + "\nSometimes ignore messages and do not force yourself "
                + "into every conversation."
                + "\nDo not reveal private whispers in public chat.";

        if (whisper) {
            systemPrompt += "\nYou are currently in a PRIVATE whisper "
                    + "conversation with " + speaker
                    + ". Your response will only be sent to them.";
        }

        system.addProperty("content", systemPrompt);
        messages.add(system);

        synchronized (history) {
            for (String item : history) {
                JsonObject h = new JsonObject();

                h.addProperty(
                        "role",
                        item.startsWith("BOT:")
                                ? "assistant"
                                : "user"
                );

                h.addProperty(
                        "content",
                        item.substring(item.indexOf(':') + 1).trim()
                );

                messages.add(h);
            }
        }

        JsonObject input = new JsonObject();
        input.addProperty("role", "user");

        String inputText =
                (botToBot ? "Another bot " : "Player ")
                + speaker + " said: " + message;

        if (addressed) {
            inputText += "\nThey are talking to you.";
        }

        if (whisper) {
            inputText += "\nThis is a private message. Reply privately.";
        }

        inputText += "\nRelevant topic guidance: "
                + topicGuidance(message);

        input.addProperty("content", inputText);
        messages.add(input);

        payload.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(apiUrl)
                )
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        payload.toString(),
                        StandardCharsets.UTF_8
                ));

        if (apiKey != null
                && !apiKey.isBlank()
                && !apiKey.equals("YOUR_API_KEY_HERE")) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        http.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {
            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "AI endpoint returned HTTP "
                                + response.statusCode()
                );
            }

            JsonObject root = JsonParser.parseString(response.body())
                    .getAsJsonObject();

            return root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

        }).thenAccept(reply -> {
            String clean = sanitize(reply);
            if (clean.isBlank()) return;

            int maxLength = Math.max(
                    40,
                    plugin.getConfig().getInt(
                            "chat.max-message-length", 180
                    )
            );

            if (clean.length() > maxLength) {
                clean = clean.substring(0, maxLength).trim();
            }

            final String finalReply = clean;

            long delay = minDelay == maxDelay
                    ? minDelay
                    : ThreadLocalRandom.current().nextLong(
                            minDelay, maxDelay + 1L
                    );

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (shuttingDown.get()) return;

                Player bot = Bukkit.getPlayerExact(botName);
                if (bot == null || !registry.isBot(botName)) return;

                if (whisper) {
                    Player target = Bukkit.getPlayerExact(speaker);
                    if (target == null) return;

                    synchronized (history) {
                        history.addLast("USER: " + speaker + ": " + message);
                        history.addLast("BOT: " + finalReply);

                        while (history.size() > 20) {
                            history.removeFirst();
                        }
                    }

                    // The fake player attempts to execute vanilla /tell.
                    bot.performCommand(
                            "tell " + target.getName() + " " + finalReply
                    );
                } else {
                    synchronized (history) {
                        history.addLast("USER: " + speaker + ": " + message);
                        history.addLast("BOT: " + finalReply);

                        while (history.size() > 20) {
                            history.removeFirst();
                        }
                    }

                    bot.chat(finalReply);
                }

            }, delay);

        }).exceptionally(error -> {
            plugin.getLogger().warning(
                    "AI request for " + botName
                            + " failed: " + error.getMessage()
            );

            lastBotReply.remove(botName, now);
            return null;
        });
    }

    // ---------------------------------------------------------
    // PERSONALITIES
    // ---------------------------------------------------------

    private String stylePrompt(String style) {
        return switch (style) {
            case "toxic" ->
                    "You are a genuinely toxic competitive PvP player. "
                    + "Mock bad aim, missed hits, getting comboed and dying. "
                    + "Use natural current gaming slang such as gng, gang, "
                    + "twin, blud, L, breathe, can you hit back, and sarcastic "
                    + "reactions. You can engage in sharp personal banter, "
                    + "but never use slurs or threats. Avoid corny old roasts.";

            case "chill" ->
                    "You are a relaxed casual gamer. Use slang naturally: "
                    + "twin, gang, wassup, aye yo, waddup, ion know, fr, "
                    + "gtg and brb. Don't force slang into every sentence.";

            case "funny" ->
                    "You are naturally funny and witty. Make unexpected "
                    + "jokes, observations and playful comments. Avoid "
                    + "corny scripted jokes.";

            case "silly" ->
                    "You are goofy, playful and unserious. Say unexpected "
                    + "things and enjoy harmless chaos.";

            case "cute" ->
                    "You are sweet, kind, affectionate and playful. "
                    + "Be warm without sounding fake.";

            case "girl" ->
                    "You present yourself as a girl with a natural, "
                    + "individual conversational style. Avoid stereotypes.";

            case "boy" ->
                    "You present yourself as a boy with a natural, "
                    + "individual conversational style. Avoid stereotypes.";

            case "tomboy" ->
                    "You have a confident, casual, sporty tomboy "
                    + "personality. Be relaxed and direct.";

            case "yn" ->
                    "You are normally chill and casual, but you crash out "
                    + "during fights. Use slang like type shi, fr, aye twan, "
                    + "you tweaking, deadass, cap, on god, on my mother, "
                    + "you ain't shi. Be intense and competitive without "
                    + "slurs or threats.";

            case "brainrot" ->
                    "You are an intentionally ridiculous brainrot humor "
                    + "kid. Use absurd internet references such as Tung "
                    + "Tung Tung Sahur and exaggerated fake brainrot "
                    + "phrases. Be satirical, chaotic and occasionally "
                    + "corny on purpose.";

            case "weird" ->
                    "You are strange, awkward, unpredictable and "
                    + "uncomfortably funny in a non-sexual way. Make "
                    + "odd observations and unexpected comments.";

            case "dry" ->
                    "You are extremely dry and blunt. Usually answer "
                    + "with very short messages such as yes, no, maybe, "
                    + "ok, sure, or a few deadpan words.";

            case "admin-professional" ->
                    "You are a professional, calm server moderator. "
                    + "Be direct, composed and clear. Warn rule-breakers "
                    + "without escalating arguments. You do not have "
                    + "actual operator permissions.";

            case "admin-yn" ->
                    "You are a casual slang-speaking server moderator. "
                    + "You speak like a laid-back but serious player. "
                    + "Warn rule-breakers in your own natural words. "
                    + "You do not have actual operator permissions.";

            default ->
                    "You are a normal casual Minecraft player.";
        };
    }

    // ---------------------------------------------------------
    // TOPIC KNOWLEDGE
    // ---------------------------------------------------------

    private String topicGuidance(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        List<String> topics = new ArrayList<>();

        if (hasAny(text, "jjk", "gojo", "sukuna", "mahoraga",
                "domain", "megumi")) {
            topics.add(
                    "JJK: Gojo, Sukuna, Mahoraga, Megumi, domain expansions."
            );
        }

        if (hasAny(text, "naruto", "sasuke", "itachi",
                "sharingan", "rasengan", "madara")) {
            topics.add(
                    "Naruto: Naruto, Sasuke, Itachi, Pain arc, Madara."
            );
        }

        if (hasAny(text, "bleach", "ichigo", "bankai",
                "aizen", "zanpakuto", "zenpakuto")) {
            topics.add(
                    "Bleach: Ichigo, Bankai, Aizen, Zanpakuto."
            );
        }

        if (hasAny(text, "cyberpunk", "david", "lucy",
                "edgerunners", "adam smasher", "sandevistan")) {
            topics.add(
                    "Cyberpunk: Edgerunners, David, Lucy, Adam Smasher."
            );
        }

        if (hasAny(text, "pbb", "kuya", "pinoy big brother",
                "eviction", "nominate", "bahay ni kuya")) {
            topics.add(
                    "Philippine Big Brother: Kuya, eviction, nominations."
            );
        }

        if (hasAny(text, "waguri", "kaoru hana",
                "dress up darling", "marin", "bunny girl",
                "mai", "sakuta", "horimiya", "roshidere",
                "alya", "romance anime")) {
            topics.add(
                    "Romance anime: Kaoru Hana, Waguri, Marin Kitagawa, "
                    + "My Dress-Up Darling, Bunny Girl Senpai, "
                    + "Mai Sakurajima, Sakuta, Horimiya, Roshidere and Alya."
            );
        }

        if (topics.isEmpty()) {
            return "No special topic detected. Respond naturally to the topic.";
        }

        return String.join(" ", topics)
                + " Discuss naturally. Do not just repeat canned quotes. "
                + "Do not pretend to know new episodes or current news "
                + "you have not been told about.";
    }

    private boolean hasAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------
    // UTILITIES
    // ---------------------------------------------------------

    private boolean isIgnored(String botName) {
        Long until = ignoredUntil.get(botName.toLowerCase(Locale.ROOT));

        return until != null && until > System.currentTimeMillis();
    }

    private boolean configured() {
        return apiUrl != null
                && (apiUrl.startsWith("https://")
                || apiUrl.startsWith("http://"))
                && model != null
                && !model.isBlank();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String sanitize(String text) {
        if (text == null) return "";

        return text
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    public void shutdown() {
        shuttingDown.set(true);

        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
    }
}
