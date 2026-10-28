package wowchat.discord;

import wowchat.common.WowChatConfig$;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GameData
 *
 * What changes between game versions, in one place: class names, the level cap, the
 * profession skill cap, which professions exist, and the level bars of the Stats post.
 * Follows the version set in wowchat.conf.
 */
public final class GameData {

    private GameData() {}

    public static final int VANILLA = 0, TBC = 1, WOTLK = 2, CATACLYSM = 3, MOP = 4;

    private static final int[] LEVEL_CAP = { 60, 70, 80, 85, 90 };
    private static final int[] SKILL_CAP = { 300, 375, 450, 525, 600 };

    private static final List<String> ALL_PROFESSIONS = Collections.unmodifiableList(Arrays.asList(
        "Alchemy", "Blacksmithing", "Enchanting", "Engineering",
        "Herbalism", "Inscription", "Jewelcrafting", "Leatherworking",
        "Mining", "Skinning", "Tailoring"
    ));

    /** The game version from wowchat.conf: VANILLA, TBC, WOTLK, CATACLYSM or MOP. */
    public static int expansion() {
        try {
            return WowChatConfig$.MODULE$.getExpansion().id();
        } catch (Throwable t) {
            return WOTLK;
        }
    }

    public static int levelCap() {
        return LEVEL_CAP[expansion()];
    }

    public static int skillCap() {
        return SKILL_CAP[expansion()];
    }

    /** Primary professions that exist in this game version, alphabetical. */
    public static List<String> professions() {
        int exp = expansion();
        List<String> result = new ArrayList<>();
        for (String p : ALL_PROFESSIONS) {
            if (p.equals("Jewelcrafting") && exp < TBC) continue;
            if (p.equals("Inscription") && exp < WOTLK) continue;
            result.add(p);
        }
        return result;
    }

    /** "Warrior", "Death Knight"..., or "Unknown". */
    public static String className(byte charClass) {
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

    /**
     * The Stats post's level bars: tens up to 59, then one bar per expansion's leveling
     * range, then the max level on its own. WotLK: 1-10 ... 51-59, 60-69, 70-79, 80.
     */
    public static List<String> levelBrackets() {
        List<String> brackets = new ArrayList<>(Arrays.asList("1-10", "11-20", "21-30", "31-40", "41-50", "51-59"));
        int exp = expansion();
        for (int i = 0; i < exp; i++) {
            int from = LEVEL_CAP[i];
            int to = LEVEL_CAP[i + 1] - 1;
            brackets.add(from + "-" + to);
        }
        brackets.add(String.valueOf(levelCap()));
        return brackets;
    }

    /** The bar a level falls in, from levelBrackets(). Above the cap counts as the cap. */
    public static String levelBracket(int level) {
        if (level >= levelCap()) return String.valueOf(levelCap());
        for (String b : levelBrackets()) {
            int dash = b.indexOf('-');
            if (dash < 0) continue;
            int to = Integer.parseInt(b.substring(dash + 1));
            if (level <= to) return b;
        }
        return String.valueOf(levelCap());
    }
}
