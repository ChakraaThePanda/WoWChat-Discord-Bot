# WoWChat-Discord-Bot
A Discord bot that connects your World of Warcraft guild to your Discord server: guild chat goes both ways, and guild lists in Discord keep themselves up to date. Made for Vanilla, The Burning Crusade, Wrath of the Lich King, Cataclysm and Mists of Pandaria servers.

---

### Features
From [WoWChat](https://github.com/fjaros/wowchat) by fjaros, which this bot started as a fork of:
* Chat Relay: guild, officer, say, yell, emote, system and custom in-game channels, mirrored to Discord channels and back
* Guild events: joins, leaves, promotions, achievements and more, posted to Discord
* WoW item and achievement links turned into database links, and Discord @mentions from WoW
* Message filters, to keep spam out of Discord
* Works without a WoW client, on Vanilla through Mists of Pandaria servers

Added in this version:
* `/menu`: Who's Online, Look Up, My Characters and Admin Actions, all with buttons
* Who's Online: a post that always shows who's online, with level, race, class and zone
* Guild Roster: every guild member grouped by Discord account, with guild stats and an officer checklist
* Promotion timers, with optional automatic promotions
* Role Sync: Discord roles follow in-game guild ranks, plus inactivity tracking
* Character linking: members prove a character is theirs with a whisper in game, and list its professions so others can find a crafter
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
* **Look Up**: level, class, zone and guild rank of any player, or who in the guild can craft a profession.
* **My Characters**: link your WoW characters to your Discord account, and add or remove their professions.
* **Admin Actions** (for the server owner and the admin roles you pick in `wowchat.conf`):
  * **Guild Ranks**: promote and demote.
  * **Ignore & Ban**: ignore, unignore, ban and unban, and see both lists.
  * **Characters**: give a character to a member, remove a link, see a member's characters, and edit anyone's professions.

Most views have **Post to channel** to share them with everyone.

#### Link your characters
Linking your characters to your Discord account shows your Discord name next to them in the guild panels and in chat, and lets you set their professions. Each member does this for their own characters:
1. In Discord, type `/menu`, press **My Characters**, then **Claim**, and type your character's name. The bot gives you a code.
2. Log in to that character and whisper the code to the bot.
3. The bot whispers back with your Discord name. Whisper `yes` to confirm.

You can unlink a character the same way, with **Unclaim**. Admins can also give a character to a member, or remove a link. Links only count in your Discord server.

---

### Questions or feedback?

Need help, found a bug, or have an idea? Join my Discord server, [Chakraa's Projects](https://discord.gg/bkk4nVXcjm), and post in this project's channel.
