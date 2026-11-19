TalkBot 2.0 - initial functional foundation

Requirements:
- Paper 1.21.11
- Java 21
- Maven 3.9+
- A reachable OpenAI-compatible chat-completions API endpoint

Build:
  mvn clean package

Output:
  target/TalkBot-2.0.0.jar

Install:
1. Stop the server.
2. Put the JAR in the server's plugins folder.
3. Start the server once to generate config.yml.
4. Configure ai.enabled, ai.api-url, ai.model and your API key.
5. Restart the server.
6. Register existing PvpBot usernames: /talkbot register <username>

API key:
- Set environment variable TALK_BOT_API_KEY on the server, OR
- Put the key in plugins/TalkBot/config.yml under ai.api-key.
- Do not share the key in chat or screenshots.

Important limitations:
- TalkBot does not create, spawn, move, equip, heal, or control PvpBot bots.
- A registered bot must appear to Bukkit as an online Player for Player#chat() to work.
- If PvpBot uses fake players that Bukkit does not expose as Player objects, TalkBot cannot make them send genuine player-originated chat using Bukkit alone. A formatted broadcast fallback would be needed and would not be genuine player chat.
- This is an initial chat foundation, not the complete planned personality/relationship/memory system.
- Bot-to-bot replies are chance-based and cooldown-limited. Do not set the chance high; chains can still happen.
- Test on a copy/staging server before production.
