# WoWChat-Discord-Bot
A Discord bot that connects your World of Warcraft guild to your Discord server: guild chat goes both ways, and guild lists in Discord keep themselves up to date. Made mainly for 3.3.5 (Wrath of the Lich King) private servers.

If you have any questions, add me on Discord: **Chakraa**

---

### Features
From [WoWChat](https://github.com/fjaros/wowchat) by fjaros, which this bot started as a fork of:
* Chat Relay: guild, officer, say, yell, emote, system and custom in-game channels, mirrored to Discord channels and back
* Guild events: joins, leaves, promotions, achievements and more, posted to Discord
* WoW item and achievement links turned into database links, and Discord @mentions from WoW
* Message filters, to keep spam out of Discord
* Works without a WoW client, on Vanilla through Mists of Pandaria servers

Added in this version:
* `/menu`: Who's Online, Look Up Player, Professions and Admin Actions, all with buttons
* Who's Online: a post that always shows who's online, with level, race, class and zone
* Guild Roster: every guild member grouped by Discord account, with guild stats and an officer checklist
* Promotion timers, with optional automatic promotions
* Role Sync: Discord roles follow in-game guild ranks, plus inactivity tracking
* Professions: members list their characters' professions so others can find a crafter
* Whisper Invite: players whisper the bot a word and get a guild invite
* Death Ping: mention a Discord role when a guild member dies
* Bot Status: the bot's Discord status shows how many players or guild members are online
* Ignore and Ban lists, class and race rules, and a DM auto-reply
* Runs from the system tray, installs Java if needed, and restarts itself if it gets stuck

---

### Setup
1. Download the [latest version](https://github.com/ChakraaThePanda/WoWChat-Discord-Bot/archive/refs/heads/main.zip) and extract it.
2. Create a Discord bot, and a WoW account and character for it in your guild.
3. Fill in `bot/wowchat.conf` and double-click `bot/Start WoWChat.bat`.

The **[setup guide](doc/setup.md)** walks you through each step, then turning on more features, and running and updating the bot.

---

### In Discord
Type `/menu` to open the bot's menu, or `/help` for a quick overview. Only you see the menu.
* **Who's Online**: who's playing right now, with level, race, class and zone.
* **Look Up Player**: level, class, zone and guild rank of any player.
* **Professions**: add or remove your characters' professions, or **Find Crafters** for one.
* **Admin Actions** (for the server owner and the admin roles you pick in `wowchat.conf`): promote, demote, ignore, unignore, ban and unban, and see the ignore and ban lists.

Most views have **Post to channel** to share them with everyone.

#### Link your characters
Linking your characters to your Discord account shows your Discord name next to them in the guild panels, and lets you set their professions. Each member does this for their own characters:
1. In Discord, turn on Developer Mode: **User Settings → Advanced → Developer Mode**.
2. Right-click your name anywhere in Discord and click **Copy User ID**.
3. In WoW, open the Guild panel and paste your User ID in your character's **Officer Note** (ask an officer) or **Public Note**, whichever your guild uses.

The bot picks it up within a few minutes.

![How to link your Discord ID](bot/assets/discord-link.png)

