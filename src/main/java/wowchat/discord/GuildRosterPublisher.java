package wowchat.discord;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigResolveOptions;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import scala.Option;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;
import wowchat.game.Player;

import java.awt.Color;
import java.io.File;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Guild Roster channel: posts and keeps updating the Audit, Statistics and Roster panels, and on
 * each update also runs the ban list, class and race rules, profession cleanup and rank timers.
 * Settings: guildDiscordLinking.guildRoster in wowchat.conf.
 */
public final class GuildRosterPublisher {

    // Config
    private static volatile List<Long>   channelIds    = Collections.emptyList();
    private static volatile int          updateMinutes = 5;
    private static volatile Set<String>  ignoreLower   = Collections.emptySet();

    // Runtime state - roster message IDs per channel
    private static volatile boolean started = false;
    private static final Map<Long, List<String>> rosterMessageIdsByChannel = new java.util.concurrent.ConcurrentHashMap<>();

    private GuildRosterPublisher() {}

    public static boolean isEnabled() { return !channelIds.isEmpty(); }
    
    private static boolean isShowUnlinkedEnabled() {
        return ConfigHelper.isShowUnlinkedCharactersEnabled();
    }

    public static synchronized void init() {
        if (started) return;
        started = true;

        loadConfig();

        // Always runs: the ban list, class and race rules and rank timers need no roster channel
        if (!channelIds.isEmpty()) {
            System.out.println("[GuildRoster] Initializing. Channel IDs: " + channelIds
                + ", update interval: " + updateMinutes + " min.");
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new GuildOnlineListPublisher.DaemonThreadFactory("wowchat-guild-audit"));

        long periodSec = Math.max(1, updateMinutes) * 60L;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Unexpected error in update tick: " + t.getMessage());
            }
        }, 20L, periodSec, TimeUnit.SECONDS);
    }

    private static void tick() {
        // Skip update if game is disconnected - prevents "Unknown Guild" embeds
        String guildIdentifier = GuildEmbedUtil.getGuildRealmIdentifier();
        if (guildIdentifier.equals("Unknown Guild (Unknown Realm)")) {
            // Bot is disconnected from game server, skip this update
            return;
        }
        // Also skip while (re)connecting, before the guild list has arrived: an empty list would
        // restart every rank timer and post an empty roster
        Option<wowchat.game.GameCommandHandler> gameNow = Global$.MODULE$.game();
        if (gameNow == null || gameNow.isEmpty() || !(gameNow.get() instanceof GamePacketHandler)
                || ((GamePacketHandler) gameNow.get()).guildRoster().isEmpty()) {
            return;
        }

        // Refresh shared cache once per tick (OPTIMIZATION)
        GuildDataCache.getInstance().refresh();

        // --- Ban list enforcement: kick any banned player found in the guild roster ---
        Collection<GuildMember> allMembers = GuildDataCache.getInstance().getMembers(false);
        Option<wowchat.game.GameCommandHandler> gameOptForKick = Global$.MODULE$.game();
        if (gameOptForKick != null && !gameOptForKick.isEmpty()
                && gameOptForKick.get() instanceof wowchat.game.GamePacketHandler) {
            wowchat.game.GamePacketHandler kickHandler =
                (wowchat.game.GamePacketHandler) gameOptForKick.get();
            scala.collection.Seq<String> bannedClasses = Global$.MODULE$.config().guildEnforcement().bannedClasses();
            scala.collection.Seq<String> bannedRaces = Global$.MODULE$.config().guildEnforcement().bannedRaces();
            String botCharacter = Global$.MODULE$.config().wow().character();
            for (GuildMember member : allMembers) {
                // Never the bot's own character
                if (member.name().equalsIgnoreCase(botCharacter)) continue;
                if (BanManager.isBanned(member.name())) {
                    System.out.println("[BanList] Kicking banned player: " + member.name());
                    kickHandler.sendGuildKick(member.name());
                    continue;
                }
                String memberClass = getClassName(member.charClass()).toLowerCase();
                if (bannedClasses.contains(memberClass)) {
                    System.out.println("[ClassBan] Kicking " + member.name() + " (" + memberClass + "): class is blacklisted.");
                    kickHandler.sendGuildKick(member.name());
                    continue;
                }
                // Race comes from name lookups, so it's unknown for a while for some members
                String memberRace = GuildOnlineListPublisher.getRace(member.name()).trim().toLowerCase();
                // endsWith: Pandaren come as "Horde Pandaren" or "Alliance Pandaren"
                boolean raceBanned = false;
                scala.collection.Iterator<String> races = bannedRaces.iterator();
                while (races.hasNext()) {
                    String r = races.next();
                    if (memberRace.equals(r) || memberRace.endsWith(" " + r)) raceBanned = true;
                }
                if (!memberRace.isEmpty() && raceBanned) {
                    System.out.println("[RaceBan] Kicking " + member.name() + " (" + memberRace + "): race is blacklisted.");
                    kickHandler.sendGuildKick(member.name());
                }
            }
        }
        
        // Clean up professions of characters who left. Every member counts here, even ignored
        // ones: they're still in the guild.
        Collection<GuildMember> currentMembers = GuildDataCache.getInstance().getMembers(false);
        Set<String> currentMemberNamesLower = new HashSet<>();
        for (GuildMember m : currentMembers) {
            currentMemberNamesLower.add(m.name().toLowerCase());
        }
        ProfessionManager.cleanupStaleEntries(currentMemberNamesLower);

        // Update rank history for the promotionDue audit sub-module
        GuildRankTracker.update(currentMembers);

        JDA jda = GuildOnlineListPublisher.getJda();
        if (jda == null) return;

        Map<String, String> rosterNotes = GuildDataCache.getInstance().getOfficerNotes(true);
        if (rosterNotes == null) return;

        // --- Get Discord guild for inactivity processing ---
        List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) return;
        Guild discordGuild = guilds.get(0);
        
        // --- Get raw roster for inactivity checking ---
        scala.collection.mutable.Map<Object, wowchat.game.GuildMember> rawRoster = GuildDataCache.getInstance().getRawRoster();
        Map<Long, Object> guildMembers = new HashMap<>();
        if (rawRoster != null) {
            scala.collection.Iterator<scala.Tuple2<Object, wowchat.game.GuildMember>> it = rawRoster.iterator();
            while (it.hasNext()) {
                scala.Tuple2<Object, wowchat.game.GuildMember> entry = it.next();
                guildMembers.put((Long) entry._1(), entry._2());
            }
        }
        
        // --- Process inactivity (assigns/removes Discord roles) - once per tick ---
        int inactiveCount = GuildInactivityManager.processInactivity(discordGuild, guildMembers);

        // --- Publish to each configured channel ---
        for (long cid : channelIds) {
            TextChannel channel = jda.getTextChannelById(cid);
            if (channel == null) {
                System.err.println("[GuildRoster] Could not find text channel with ID " + cid + ".");
                continue;
            }

            // Panels turned off in wowchat.conf remove their post
            removeIfOff(channel, "Guild Sync Audit", ConfigHelper.isAuditEnabled());
            removeIfOff(channel, "Guild Statistics", ConfigHelper.isStatsEnabled());
            if (removeIfOff(channel, "Guild Roster", ConfigHelper.isRosterPanelEnabled())) {
                rosterMessageIdsByChannel.remove(channel.getIdLong());
            }

            // --- Build and post audit embed ---
            GuildDiscordAuditPublisher.publish(channel);
            
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            // --- Build and post stats embed ---
            GuildStatsPublisher.publish(channel);
            
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            // --- Build roster embed (only if enabled) ---
            if (ConfigHelper.isRosterPanelEnabled()) {
                postOrEditRoster(channel, discordGuild, rosterNotes, inactiveCount);
            }
        }
    }

    // Panels already removed per channel, so the channel is only searched once while one stays off
    private static final Map<Long, Set<String>> removedPanels = new java.util.concurrent.ConcurrentHashMap<>();

    /** Removes the bot's post for a panel that's turned off. True when it just removed it. */
    private static boolean removeIfOff(TextChannel channel, String title, boolean on) {
        Set<String> removed = removedPanels.computeIfAbsent(channel.getIdLong(),
            k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (on) {
            removed.remove(title);
            return false;
        }
        if (!removed.add(title)) return false;
        GuildEmbedUtil.deleteStrayPages(channel, title, Collections.emptySet());
        return true;
    }

    // -------------------------------------------------------------------------
    // Format a single character entry: "Name (Level X Race Class)"
    // -------------------------------------------------------------------------

    private static String getClassName(byte charClass) {
        switch (charClass) {
            case 0x01: return "Warrior";
            case 0x02: return "Paladin";
            case 0x03: return "Hunter";
            case 0x04: return "Rogue";
            case 0x05: return "Priest";
            case 0x06: return "Death Knight";
            case 0x07: return "Shaman";
            case 0x08: return "Mage";
            case 0x09: return "Warlock";
            case 0x0A: return "Monk";
            case 0x0B: return "Druid";
            default:   return "Unknown";
        }
    }

    private static String formatCharEntry(String charName, scala.collection.Map<Object, GuildMember> roster) {
        GuildMember m = GuildDataCache.getInstance().getMember(charName);
        if (m == null) {
            return charName + " (Level ? Unknown)";
        }
        
        String race = GuildOnlineListPublisher.getRace(charName);
        String cls = getClassName(m.charClass());
        int level = m.level() & 0xFF; // byte to unsigned int
        String attrs = "Level " + level
            + (race.isEmpty() ? "" : " " + race.trim())
            + " " + cls;
        StringBuilder entry = new StringBuilder(charName + " (" + attrs + ")");
        java.util.List<String> profs = ProfessionManager.getProfessions(charName);
        for (String prof : profs) {
            entry.append("\n  \u2022 ").append(ProfessionManager.formatProfession(prof));
        }
        return entry.toString();
    }

    // -------------------------------------------------------------------------
    // Guild Roster embed
    // -------------------------------------------------------------------------

    private static void postOrEditRoster(TextChannel channel, Guild discordGuild, Map<String, String> rosterNotes, int inactiveCount) {
        // Get full roster for attribute lookup
        scala.collection.Map<Object, GuildMember> finalRoster = null;
        try {
            Option<wowchat.game.GameCommandHandler> gameOpt = Global$.MODULE$.game();
            if (gameOpt != null && !gameOpt.isEmpty() && gameOpt.get() instanceof wowchat.game.GamePacketHandler) {
                finalRoster = ((wowchat.game.GamePacketHandler) gameOpt.get()).guildRoster();
            }
        } catch (Throwable ignored) {}

        // Build list of user blocks: each block is one Discord user + their characters
        // A block is a self-contained string that must never be split across pages
        List<String> blocks = new ArrayList<>();

        // Sort all characters alphabetically
        List<String> sortedChars = new ArrayList<>(rosterNotes.keySet());
        Collections.sort(sortedChars, String.CASE_INSENSITIVE_ORDER);

        // Group by Discord ID, preserving alphabetical order within each group
        Map<String, List<String>> byDiscordId = new LinkedHashMap<>();
        List<String> unlinked = new ArrayList<>();

        // Collect all Discord IDs that appear in notes first
        Map<String, String> discordIdByChar = new LinkedHashMap<>();
        for (String charName : sortedChars) {
            if (ignoreLower.contains(charName.toLowerCase(Locale.ROOT))) continue;
            GuildMember member = GuildDataCache.getInstance().getMember(charName);
            String discordId = DiscordIdExtractor.extractDiscordId(member);
            if (discordId != null) discordIdByChar.put(charName, discordId);
        }

        // Find member across all guilds the bot is in (cache lookup, no API calls)
        Map<String, Member> fetchedMembers = new HashMap<>();
        Set<String> uniqueIds = new HashSet<>(discordIdByChar.values());
        JDA jdaRef = GuildOnlineListPublisher.getJda();
        if (jdaRef != null) {
            for (String discordId : uniqueIds) {
                for (Guild g : jdaRef.getGuilds()) {
                    Member m = g.getMemberById(discordId);
                    if (m != null) { fetchedMembers.put(discordId, m); break; }
                }
            }
        }

        for (String charName : sortedChars) {
            if (ignoreLower.contains(charName.toLowerCase(Locale.ROOT))) continue;
            String discordId = discordIdByChar.get(charName);
            if (discordId != null && fetchedMembers.containsKey(discordId)) {
                byDiscordId.computeIfAbsent(discordId, k -> new ArrayList<>()).add(charName);
            } else {
                unlinked.add(charName);
            }
        }

        // Sort linked users by Discord display name
        List<Map.Entry<String, List<String>>> linkedEntries = new ArrayList<>(byDiscordId.entrySet());
        linkedEntries.sort((a, b) -> {
            Member ma = fetchedMembers.get(a.getKey());
            Member mb = fetchedMembers.get(b.getKey());
            String na = ma != null ? ma.getEffectiveName() : a.getKey();
            String nb = mb != null ? mb.getEffectiveName() : b.getKey();
            return na.compareToIgnoreCase(nb);
        });

        // Build one block per linked user
        for (Map.Entry<String, List<String>> entry : linkedEntries) {
            StringBuilder block = new StringBuilder();
            Member m = fetchedMembers.get(entry.getKey());
            String displayName = m != null ? m.getEffectiveName() : entry.getKey();
            int charCount = entry.getValue().size();
            block.append("<@").append(entry.getKey()).append("> - **").append(displayName).append("** (").append(charCount).append(")\n");
            for (String charName : entry.getValue()) {
                block.append("- ").append(finalRoster != null ? formatCharEntry(charName, finalRoster) : charName).append("\n");
            }
            blocks.add(block.toString());
        }

        // Unlinked section - show count and instructions, optionally list characters
        if (!unlinked.isEmpty()) {
            if (isShowUnlinkedEnabled()) {
                // Show full list of unlinked characters
                blocks.add("**Unlinked Characters (" + unlinked.size() + ")**\n");
                
                for (String charName : unlinked) {
                    String entry = "- " + (finalRoster != null ? formatCharEntry(charName, finalRoster) : charName) + "\n";
                    blocks.add(entry);
                }
            } else {
                // Show count and instructions only
                blocks.add("**Unlinked Characters (" + unlinked.size() + ")**\n"
                    + "_Link your characters in-game to appear in this roster._\n"
                    + "_Type `/help` in Discord to see how._\n");
            }
        }

        // Pack blocks into pages - never split a user block across pages
        // Reserve space on page 1 for the linked players + level 80s prefix
        int uniquePlayersCount = byDiscordId.size();
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        final int PAGE_LIMIT = 3800;
        final int FIRST_PAGE_LIMIT = PAGE_LIMIT - 50; // reserve ~50 chars for prefix on page 1

        for (String block : blocks) {
            int limit = pages.isEmpty() ? FIRST_PAGE_LIMIT : PAGE_LIMIT;
            if (currentPage.length() + block.length() > limit && currentPage.length() > 0) {
                pages.add(currentPage.toString());
                currentPage = new StringBuilder();
            }
            currentPage.append(block);
        }
        if (currentPage.length() > 0) pages.add(currentPage.toString());
        if (pages.isEmpty()) pages.add("No guild members found.");

        // Grow or shrink per-channel rosterMessageIds list to match page count
        long channelKey = channel.getIdLong();
        rosterMessageIdsByChannel.putIfAbsent(channelKey, new ArrayList<>());
        List<String> rosterMessageIds = rosterMessageIdsByChannel.get(channelKey);

        while (rosterMessageIds.size() < pages.size()) rosterMessageIds.add(null);
        while (rosterMessageIds.size() > pages.size()) {
            // Delete extra messages that are no longer needed
            String extraId = rosterMessageIds.remove(rosterMessageIds.size() - 1);
            if (extraId != null) {
                try { channel.deleteMessageById(extraId).complete(); }
                catch (Throwable ignored) {}
            }
        }
        // Pages we had to look up (after a restart or a deleted post) may leave old ones behind
        boolean lookedUpPages = rosterMessageIds.contains(null);

        // Post or edit each page
        int totalMembers = sortedChars.size() - (int) sortedChars.stream().filter(n -> ignoreLower.contains(n.toLowerCase(Locale.ROOT))).count();
        // Post pages in forward order (1, 2, 3...)
        // New pages appear at bottom, edits stay in place
        for (int i = 0; i < pages.size(); i++) {
            String title = pages.size() > 1 ? "Guild Roster (" + totalMembers + ") (" + (i + 1) + "/" + pages.size() + ")" : "Guild Roster (" + totalMembers + ")";
            
            // Build description prefix for first page
            StringBuilder prefix = new StringBuilder();
            if (i == 0) {
                prefix.append(uniquePlayersCount).append(" Linked Players");
                if (inactiveCount > 0) {
                    prefix.append(" (").append(inactiveCount).append(" Inactive)");
                }
                prefix.append("\n\n");
            }
            String description_prefix = prefix.toString();
            
            // Footer: all pages get the footer for identification
            String footerText = GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + GuildEmbedUtil.getFormattedDate();
            
            MessageEmbed embed = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description_prefix + pages.get(i))
                .setColor(Color.decode("#2b2d31"))
                .setFooter(footerText)
                .build();

            final int idx = i;
            postOrEditEmbed(channel, embed, title,
                id -> rosterMessageIds.set(idx, id),
                () -> rosterMessageIds.get(idx),
                id -> rosterMessageIds.set(idx, null));
            
            // Delay between pages to avoid Discord rate limits
            if (i < pages.size() - 1) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        if (lookedUpPages) GuildEmbedUtil.deleteStrayPages(channel, "Guild Roster", rosterMessageIds);
    }

    // -------------------------------------------------------------------------
    // Post or edit an embed message
    // -------------------------------------------------------------------------

    private interface Setter { void set(String id); }
    private interface Getter { String get(); }

    private static void postOrEditEmbed(TextChannel channel, MessageEmbed embed, String pageTitle,
                                        Setter setId, Getter getId, Setter clearId) {
        if (getId.get() == null) {
            setId.set(GuildEmbedUtil.findPagedEmbed(channel, pageTitle));
        }

        if (getId.get() == null) {
            try {
                Message sent = channel.sendMessageEmbeds(embed)
                    .complete();
                setId.set(sent.getId());
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Failed to send embed: " + t.getMessage());
            }
        } else {
            try {
                channel.editMessageById(getId.get(), " ")
                    .setEmbeds(embed)
                    .complete();
            } catch (Throwable t) {
                System.err.println("[GuildRoster] Failed to edit embed (will retry): " + t.getMessage());
                clearId.set(null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    private static void loadConfig() {
        try {
            String configFile = System.getProperty("wowchat.configFile", "wowchat.conf");
            Config config = ConfigFactory.parseFile(new File(configFile))
                .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));

            updateMinutes = 5;
            try {
                if (config.hasPath("discord.featuresUpdateMinutes")) {
                    updateMinutes = config.getInt("discord.featuresUpdateMinutes");
                }
                if (updateMinutes < 1) updateMinutes = 1;
            } catch (ConfigException ignored) {}

            if (!ConfigHelper.isGuildRosterEnabled()) {
                channelIds = Collections.emptyList();
                ignoreLower = Collections.emptySet();
                return;
            }

            channelIds = ConfigHelper.getGuildRosterChannelIds();

            Set<String> ignoreSet = new HashSet<>();
            // The bot's own character is never listed
            if (config.hasPath("wow.character")) {
                ignoreSet.add(config.getString("wow.character").trim().toLowerCase(Locale.ROOT));
            }
            try {
                // Check both old and new paths for ignore list
                String ignorePath = config.hasPath("guildOnlineList.ignore")
                    ? "guildOnlineList.ignore"
                    : "guildOnlineListIgnore";
                
                if (config.hasPath(ignorePath)) {
                    for (String name : config.getStringList(ignorePath)) {
                        if (name != null && !name.trim().isEmpty())
                            ignoreSet.add(name.trim().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Throwable ignored) {}
            ignoreLower = ignoreSet.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ignoreSet);
            // Stats and the audit leave the same characters out
            GuildDataCache.getInstance().setIgnoreList(ignoreLower);

        } catch (Throwable t) {
            System.err.println("[GuildRoster] Failed to load config: " + t.getMessage());
            channelIds = Collections.emptyList();
        }
    }
}
