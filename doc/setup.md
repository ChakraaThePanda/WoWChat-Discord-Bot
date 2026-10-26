# Setting up WoWChat-Discord-Bot

This guide takes you from nothing to a running bot. The bot runs on Windows.

---

### 1. Download the bot
Download the [latest version](https://github.com/ChakraaThePanda/WoWChat-Discord-Bot/archive/refs/heads/main.zip) and extract it somewhere it can stay, like `Documents\WoWChat`. Everything the bot needs is in its `bot` folder.

### 2. Create the Discord bot
1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and click **New Application**. Give it a name.
2. In **Bot**:
   * Click **Reset Token** and copy the token. Keep it private: anyone with it can control your bot.
   * Turn on **Presence Intent**, **Server Members Intent** and **Message Content Intent**.
3. In **OAuth2**, tick `bot` and `applications.commands`, then these permissions:
   * **View Channels**, **Send Messages**, **Embed Links** and **Read Message History**
   * **Manage Roles**, only if you'll use Role Sync or inactivity tracking. Also drag the bot's role above the roles it should give out, in your Discord server's role settings.
4. Open the link at the bottom of the page and invite the bot to your server.

### 3. Create the bot's WoW character
1. Make a WoW account and a character for the bot, and invite that character to your guild.
2. Give it a guild rank that can do what you'll ask of it:

| To use | The bot's rank needs |
|---|---|
| Discord IDs in officer notes (`noteLocation = "officer"`) | View Officer Note |
| Promote and Demote in `/menu`, automatic promotions | Promote and Demote (only works on ranks below its own) |
| Ban list, class and race rules | Remove Member |
| Whisper Invite | Invite Member |

### 4. Fill in the config
Open `bot/wowchat.conf` in a text editor (Notepad works) and replace each `FILL_ME_IN`:
* `token`: the Discord bot token from step 2
* `realmlist` and `realm`: your server's address and realm name, as in your WoW `realmlist.wtf`
* `account`, `password` and `character`: the bot's WoW login from step 3

Set `version` to your server's game version (the list of versions is right below it in the file). Save the file.

### 5. Start the bot
Double-click `bot/Start WoWChat.bat`. The first start installs Java if it's missing (a Windows permission popup may appear). If that fails, install [Java](https://adoptium.net/) yourself and start it again.

That's all the bot needs: type `/menu` in Discord to try it.

---

### Turning on more features
Everything else in `wowchat.conf` is optional, off until you turn it on, and explained right in the file. Each feature has an `enabled = true` / `enabled = false` switch.

To find a channel or role ID, turn on Developer Mode in Discord (**User Settings → Advanced → Developer Mode**), then right-click the channel or role and click **Copy ID**. An ID left at `000000000000000000` means "not set".

| Feature | Config section | What to set |
|---|---|---|
| Chat Relay | `chat` | A Discord channel ID for each chat you want relayed (guild, officer, say, system...) |
| Guild events (joins, promotions...) | `guild` | Which events to post. They go to the guild chat channel. |
| Who's Online | `guildOnlineList` | The channel to post the list in |
| Bot Status | `guildStatus` | The status messages, with `{guild-online-members}` or `{server-online-members}` |
| Guild Roster, Stats and Audit | `guildDiscordLinking` → `guildRoster` | The channel, and which panels and audit checks to show |
| Role Sync | `guildDiscordLinking` → `roleSync` | Which Discord role goes with each guild rank |
| Inactivity tracking | `guildDiscordLinking` → `inactivity` | How many days, and the role to give |
| Death Ping | `guildDeathNotifications` | The role to ping. Deaths come in as system messages, so relay those too. |
| Whisper Invite | `autoGuildInvite` | The trigger words |
| Class and race rules | `guildEnforcement` | Classes and races to kick |
| DM auto-reply | `dmAutoReply` | The reply text |
| Admin roles | `discord` → `commandRoleIds` | Discord roles that can use Admin Actions in `/menu` |

Most settings are read when the bot starts, so restart it after changing the config (right-click the tray logo, **Restart bot**). If the config has a mistake, the bot stops and the log says which line to fix.

#### Things that depend on your WoW server
Private servers differ, so a few features only work if the server supports them:
* **Death Ping** looks for the server's death messages. If your server words them differently, change `patterns` in `guildDeathNotifications` to match.
* **`{server-online-members}`** asks the server with `.server info`. If players can't use that command, the bot counts with `/who` instead, which usually only sees one faction.
* **Look Up Player** uses `/who`, which on most servers only finds players of the bot's faction.
* **Warden** (anti-cheat): if the bot keeps getting disconnected, set `platform = Mac` in the `wow` section.

---

### Running the bot
The bot runs in the background, with its logo in the system tray next to the clock. Right-click the logo to **Show log**, **Restart bot** or **Stop bot and exit**. A red dot on the logo means the bot stopped; open the log to see why.

If the bot loses its connection to WoW or Discord, it reconnects or restarts itself.

* **Start with Windows**: press `Win + R`, type `shell:startup`, and put a shortcut to `Start WoWChat.bat` in that folder.
* **Run in a window instead**: from a terminal in the `bot` folder, run `"Start WoWChat.bat" console`.

### Updating
Extract the new version to a new folder, then copy `wowchat.conf` and the `data` folder from your old `bot` folder into the new one. This keeps your settings, professions, and ignore and ban lists. New settings come with their defaults, so compare with the new `wowchat.conf` if you want to try them.

Coming from an older version that started with `run.bat`? Do the same, then start the bot with `Start WoWChat.bat` instead, and update your Windows startup shortcut if you had one.
