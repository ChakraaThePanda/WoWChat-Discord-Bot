package wowchat.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import wowchat.common.Global$;
import wowchat.common.WowChatConfig$;
import wowchat.game.GuildMember;

import java.awt.Color;
import java.util.*;

/**
 * GuildStatsPublisher
 *
 * Publishes guild statistics (faction/race/class distribution, profession counts)
 * as a Discord embed. Called directly from GuildRosterPublisher.tick() to ensure
 * deterministic ordering: Audit -> Stats -> Roster.
 */
public final class GuildStatsPublisher {

    private static final java.util.concurrent.ConcurrentHashMap<Long, String> messageIdByChannel =
        new java.util.concurrent.ConcurrentHashMap<>();

    private GuildStatsPublisher() {}

    // Called from GuildRosterPublisher.tick() - posts stats between Audit and Roster
    public static void publish(TextChannel channel) {
        if (!isStatsEnabled()) return;

        long channelKey = channel.getIdLong();

        try {
            MessageEmbed embed = buildStatsEmbed();

            // Try to find existing message on first run for this channel
            if (!messageIdByChannel.containsKey(channelKey)) {
                String found = GuildEmbedUtil.findEmbedByTitleAndFooter(channel, "Guild Statistics");
                if (found != null) messageIdByChannel.put(channelKey, found);
            }

            String messageId = messageIdByChannel.get(channelKey);

            // Post new or edit existing
            if (messageId == null) {
                try {
                    net.dv8tion.jda.api.entities.Message sent = channel.sendMessageEmbeds(embed)
                        .complete();
                    messageIdByChannel.put(channelKey, sent.getId());
                } catch (Throwable t) {
                    System.err.println("[GuildStats] Failed to send embed: " + t.getMessage());
                }
            } else {
                try {
                    channel.editMessageById(messageId, " ")
                        .setEmbeds(embed)
                        .complete();
                } catch (Throwable t) {
                    System.err.println("[GuildStats] Failed to edit embed (will retry): " + t.getMessage());
                    messageIdByChannel.remove(channelKey);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildStats] Publish error: " + t.getMessage());
        }
    }

    private static boolean isStatsEnabled() {
        return ConfigHelper.isStatsEnabled();
    }

    private static List<String> filterBanned(List<String> names, List<String> banned) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (!banned.contains(name.toLowerCase())) {
                result.add(name);
            }
        }
        return result;
    }

    private static MessageEmbed buildStatsEmbed() {
        Collection<GuildMember> members = GuildDataCache.getInstance().getMembers(true);

        int expansionId = GameData.expansion();
        List<String> bannedClasses = ConfigHelper.getBannedClasses();
        List<String> bannedRaces = ConfigHelper.getBannedRaces();

        // --- Build active class list ---
        List<String> allClasses = new ArrayList<>(Arrays.asList(
            "Druid", "Hunter", "Mage", "Paladin", "Priest", "Rogue", "Shaman", "Warlock", "Warrior"
        ));
        if (expansionId >= 2) allClasses.add("Death Knight"); // WotLK+
        if (expansionId >= 4) allClasses.add("Monk");         // MoP+
        List<String> activeClasses = filterBanned(allClasses, bannedClasses);
        Collections.sort(activeClasses);

        // --- Build active race lists (per faction, alphabetical within each) ---
        List<String> allianceRaces = new ArrayList<>(Arrays.asList("Dwarf", "Gnome", "Human", "Night Elf"));
        List<String> hordeRaces    = new ArrayList<>(Arrays.asList("Orc", "Tauren", "Troll", "Undead"));
        if (expansionId >= 1) { // TBC+
            allianceRaces.add("Draenei");
            hordeRaces.add("Blood Elf");
        }
        if (expansionId >= 3) { // Cataclysm+
            allianceRaces.add("Worgen");
            hordeRaces.add("Goblin");
        }
        Collections.sort(allianceRaces);
        Collections.sort(hordeRaces);

        List<String> neutralRaces = new ArrayList<>();
        if (expansionId >= 4) neutralRaces.add("Pandaren"); // MoP+

        List<String> activeAlliance = filterBanned(allianceRaces, bannedRaces);
        List<String> activeHorde    = filterBanned(hordeRaces, bannedRaces);
        List<String> activeNeutral  = filterBanned(neutralRaces, bannedRaces);

        // --- Initialize counts ---
        Map<String, Integer> factionCounts = new LinkedHashMap<>();
        factionCounts.put("Alliance", 0);
        factionCounts.put("Horde", 0);

        Map<String, Integer> raceCounts = new LinkedHashMap<>();
        for (String r : activeAlliance) raceCounts.put(r, 0);
        for (String r : activeHorde)    raceCounts.put(r, 0);
        for (String r : activeNeutral)  raceCounts.put(r, 0);

        Map<String, Integer> classCounts = new LinkedHashMap<>();
        for (String c : activeClasses) classCounts.put(c, 0);

        Map<String, Integer> levelCounts = new LinkedHashMap<>();
        for (String bracket : GameData.levelBrackets()) levelCounts.put(bracket, 0);

        for (GuildMember m : members) {
            // Faction
            String rawRace = GuildOnlineListPublisher.getRace(m.name());
            String faction = getFaction(rawRace.trim());
            if (factionCounts.containsKey(faction)) {
                factionCounts.put(faction, factionCounts.get(faction) + 1);
            }

            // Race - normalize Pandaren variants to a single key
            String raceName = rawRace.trim();
            if (raceName.equals("Alliance Pandaren") || raceName.equals("Horde Pandaren")) {
                raceName = "Pandaren";
            }
            if (!raceName.isEmpty() && raceCounts.containsKey(raceName)) {
                raceCounts.put(raceName, raceCounts.get(raceName) + 1);
            }

            // Class - only count if in active list (respects expansion + banned)
            String cls = GameData.className(m.charClass());
            if (classCounts.containsKey(cls)) {
                classCounts.put(cls, classCounts.get(cls) + 1);
            }

            // Level bars follow the game version (GameData.levelBrackets)
            int level = m.level() & 0xFF;
            if (level >= 1) levelCounts.merge(GameData.levelBracket(level), 1, Integer::sum);
        }

        Map<String, Integer> profCounts = getProfessionCounts();

        int totalMembers = members.size();

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Guild Statistics");
        eb.setColor(Color.decode("#2b2d31"));
        eb.setFooter(GuildEmbedUtil.getGuildRealmIdentifier() + " - Last updated: " + GuildEmbedUtil.getFormattedDate());

        if (ConfigHelper.isStatsBlockEnabled("show_faction_distribution")) {
            eb.addField("Faction Distribution", buildFactionGraph(factionCounts, totalMembers), false);
        }

        if (ConfigHelper.isStatsBlockEnabled("show_race_distribution")) {
            String raceDistribution = raceCounts.isEmpty()
                ? "Race data not yet available"
                : buildRaceGraph(raceCounts, totalMembers, activeAlliance, activeHorde, activeNeutral);
            eb.addField("Race Distribution", raceDistribution, false);
        }

        if (ConfigHelper.isStatsBlockEnabled("show_class_distribution")) {
            eb.addField("Class Distribution", buildClassGraph(classCounts, totalMembers, activeClasses), false);
        }

        if (ConfigHelper.isStatsBlockEnabled("show_level_distribution")) {
            eb.addField("Level Distribution", buildLevelGraph(levelCounts, totalMembers), false);
        }

        if (ConfigHelper.isStatsBlockEnabled("show_professions")) {
            eb.addField("Professions", buildProfessionList(profCounts), false);
        }

        return eb.build();
    }

    private static String getFaction(String race) {
        if (race == null || race.trim().isEmpty()) return "Unknown";
        race = race.trim();
        Set<String> horde    = new HashSet<>(Arrays.asList("Orc", "Undead", "Tauren", "Troll", "Blood Elf", "Goblin", "Horde Pandaren"));
        Set<String> alliance = new HashSet<>(Arrays.asList("Draenei", "Dwarf", "Gnome", "Human", "Night Elf", "Worgen", "Alliance Pandaren"));
        if (horde.contains(race))    return "Horde";
        if (alliance.contains(race)) return "Alliance";
        return "Unknown";
    }

    // Faction graph: Always show both factions in order (Alliance, Horde)
    private static String buildFactionGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";

        final int MAX_BAR_LENGTH = 20;

        int maxCount = Math.max(counts.getOrDefault("Alliance", 0), counts.getOrDefault("Horde", 0));
        if (maxCount == 0) return "No data";

        int maxDigits = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder("```\n");
        for (String faction : Arrays.asList("Alliance", "Horde")) {
            int count = counts.getOrDefault(faction, 0);
            int pct  = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);

            sb.append(String.format("%-17s ", faction + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    // Race graph: Alliance first, then Horde, then Neutral (Pandaren) - all alphabetical within group
    private static String buildRaceGraph(Map<String, Integer> counts, int total,
            List<String> allianceRaces, List<String> hordeRaces, List<String> neutralRaces) {
        if (total == 0) return "No data";

        final int MAX_BAR_LENGTH = 20;

        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";

        int maxDigits = String.valueOf(maxCount).length();

        List<String> orderedRaces = new ArrayList<>();
        orderedRaces.addAll(allianceRaces);
        orderedRaces.addAll(hordeRaces);
        orderedRaces.addAll(neutralRaces);

        StringBuilder sb = new StringBuilder("```\n");
        for (String race : orderedRaces) {
            if (!counts.containsKey(race)) continue;
            int count = counts.get(race);
            int pct  = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);

            sb.append(String.format("%-17s ", race + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    // Class graph: alphabetical order, only active classes
    private static String buildClassGraph(Map<String, Integer> counts, int total, List<String> classOrder) {
        if (total == 0) return "No data";

        final int MAX_BAR_LENGTH = 20;

        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";

        int maxDigits = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder("```\n");
        for (String cls : classOrder) {
            if (!counts.containsKey(cls)) continue;
            int count = counts.get(cls);
            int pct  = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);

            sb.append(String.format("%-17s ", cls + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    // Level graph: bracket order
    private static String buildLevelGraph(Map<String, Integer> counts, int total) {
        if (total == 0) return "No data";

        final int MAX_BAR_LENGTH = 20;

        int maxCount = counts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxCount == 0) return "No data";

        int maxDigits = String.valueOf(maxCount).length();

        List<String> levelOrder = GameData.levelBrackets();

        StringBuilder sb = new StringBuilder("```\n");
        for (String bracket : levelOrder) {
            int count = counts.getOrDefault(bracket, 0);
            int pct  = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);

            sb.append(String.format("%-17s ", bracket + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    private static String buildProfessionList(Map<String, Integer> profCounts) {
        if (profCounts.isEmpty()) return "No professions registered.";

        final int MAX_BAR_LENGTH = 20;

        List<String> profs = new ArrayList<>(profCounts.keySet());
        Collections.sort(profs);

        int maxCount = profCounts.values().stream().max(Integer::compareTo).orElse(1);
        int total    = profCounts.values().stream().mapToInt(Integer::intValue).sum();
        int maxDigits = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder("```\n");
        for (String prof : profs) {
            int count = profCounts.get(prof);
            int pct  = (int) Math.round((count * 100.0) / total);
            int bars = (int) Math.round((count / (double) maxCount) * MAX_BAR_LENGTH);

            sb.append(String.format("%-17s ", prof + ":"));
            for (int i = 0; i < (MAX_BAR_LENGTH - bars); i++) sb.append(" ");
            for (int i = 0; i < bars; i++) sb.append("=");
            sb.append(String.format(" %" + maxDigits + "d (%d%%)      \n", count, pct));
        }
        sb.append("```");
        return sb.toString();
    }

    private static Map<String, Integer> getProfessionCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();

        // Only the professions that exist in this game version
        for (String p : GameData.professions()) counts.put(p, 0);

        try {
            Collection<GuildMember> members = GuildDataCache.getInstance().getMembers(true);

            for (GuildMember m : members) {
                List<String> profs = ProfessionManager.getProfessions(m.name());
                for (String stored : profs) {
                    String prof = ProfessionManager.profName(stored);
                    if (counts.containsKey(prof)) counts.put(prof, counts.get(prof) + 1);
                }
            }
        } catch (Throwable t) {
            System.err.println("[GuildStats] Profession count error: " + t.getMessage());
        }

        return counts;
    }
}
