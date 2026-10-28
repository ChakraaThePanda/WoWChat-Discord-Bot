package wowchat.discord;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import wowchat.game.GuildMember;

import java.util.*;

/**
 * ProfessionManager
 *
 * Up to 2 primary professions per guild character, stored in data/characters.json
 * (see CharacterStore).
 *
 * Permissions:
 *   - A Discord user can only change characters in the guild that are linked to them.
 *   - Bot admins (bot owner, server owner, discord.commandRoleIds) can change any guild character.
 */
public final class ProfessionManager {

    public static final int MAX_PROFESSIONS = 2;

    // Discord role IDs allowed to manage professions for any character.
    // Loaded from discord.commandRoleIds in wowchat.conf.
    private static final Set<String> commandRoleIds = new LinkedHashSet<>();

    static {
        loadCommandRoles();
    }

    private ProfessionManager() {}

    /** The professions that exist in this game version. */
    public static List<String> validProfessions() {
        return GameData.professions();
    }

    /** Characters with the given profession: [charName, storedValue e.g. "Herbalism:289"]. */
    public static List<String[]> listByProfession(String profession) {
        return CharacterStore.withProfession(profession);
    }

    public static List<String> getProfessions(String charName) {
        GuildMember m = GuildDataCache.findMember(charName);
        if (m != null) return new ArrayList<>(CharacterStore.professions(m.guid()));
        CharacterStore.Record r = CharacterStore.byName(charName);
        return r == null ? new ArrayList<>() : new ArrayList<>(r.professions);
    }

    /** Returns display string e.g. "Herbalism (289)" or "Herbalism" */
    public static String formatProfession(String stored) {
        int sep = stored.indexOf(':');
        if (sep < 0) return stored;
        return stored.substring(0, sep) + " (" + stored.substring(sep + 1) + ")";
    }

    /** Returns just the profession name without skill level */
    public static String profName(String stored) {
        int sep = stored.indexOf(':');
        return sep < 0 ? stored : stored.substring(0, sep);
    }

    /**
     * Sets a profession for a character.
     * Returns null on success, or an error message string on failure.
     */
    public static synchronized String add(String charName, String profession, Integer skillLevel, String callerDiscordId, Guild guild) {
        if (!validProfessions().contains(profession)) {
            return "Invalid profession. Valid options: " + String.join(", ", validProfessions());
        }
        int cap = GameData.skillCap();
        if (skillLevel != null && (skillLevel < 1 || skillLevel > cap)) {
            return "Skill level must be between 1 and " + cap + ".";
        }
        GuildMember member = GuildDataCache.findMember(charName);
        if (member == null) {
            return "**" + charName + "** is not a member of the guild.";
        }
        if (!canModify(callerDiscordId, member, guild)) {
            return "You can only add professions for your own linked characters.";
        }

        String stored = skillLevel != null ? profession + ":" + skillLevel : profession;
        List<String> profs = new ArrayList<>(CharacterStore.professions(member.guid()));

        // Check if profession already exists (update it)
        boolean updated = false;
        for (int i = 0; i < profs.size(); i++) {
            if (profName(profs.get(i)).equals(profession)) {
                profs.set(i, stored);
                updated = true;
                break;
            }
        }

        if (!updated) {
            if (profs.size() >= MAX_PROFESSIONS) {
                List<String> profNames = new ArrayList<>();
                for (String p : profs) profNames.add(profName(p));
                return member.name() + " already has " + MAX_PROFESSIONS + " professions (" + String.join(", ", profNames) + "). Remove one first.";
            }
            profs.add(stored);
        }

        CharacterStore.setProfessions(member.guid(), member.name(), profs);
        return null;
    }

    /**
     * Removes a profession from a character.
     * Returns null on success, or an error message string on failure.
     */
    public static synchronized String remove(String charName, String profession, String callerDiscordId, Guild guild) {
        GuildMember member = GuildDataCache.findMember(charName);
        if (member == null) {
            return "**" + charName + "** is not a member of the guild.";
        }
        if (!canModify(callerDiscordId, member, guild)) {
            return "You can only remove professions from your own linked characters.";
        }

        List<String> profs = new ArrayList<>(CharacterStore.professions(member.guid()));
        boolean removed = profs.removeIf(p -> profName(p).equals(profession));
        if (!removed) {
            return member.name() + " does not have " + profession + ".";
        }
        CharacterStore.setProfessions(member.guid(), member.name(), profs);
        return null;
    }

    // -------------------------------------------------------------------------
    // Permission check
    // -------------------------------------------------------------------------

    public static boolean hasCommandRole(Member member) {
        if (member == null) return false;
        return member.getRoles().stream().anyMatch(r -> commandRoleIds.contains(r.getId()));
    }

    private static boolean canModify(String callerDiscordId, GuildMember member, Guild guild) {
        if (MenuHandler.isAdmin(callerDiscordId, guild)) return true;
        return callerDiscordId != null && callerDiscordId.equals(CharacterStore.discordIdOf(member));
    }

    private static void loadCommandRoles() {
        try {
            List<String> ids = ConfigHelper.getCommandRoleIds();
            for (String roleId : ids) {
                roleId = roleId.trim();
                if (!roleId.isEmpty()) commandRoleIds.add(roleId);
            }
            if (!commandRoleIds.isEmpty()) {
                System.out.println("[Bot] Command roles loaded: " + commandRoleIds);
            }
        } catch (Throwable t) {
            System.err.println("[ProfessionManager] Could not load command roles: " + t.getMessage());
        }
    }
}
