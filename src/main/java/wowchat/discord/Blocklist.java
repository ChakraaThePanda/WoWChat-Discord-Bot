package wowchat.discord;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import wowchat.game.GamePacketHandler;
import wowchat.game.GuildMember;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocklist
 *
 * The ban and ignore lists, in data/blocklist.json, and the rules that keep players out of the
 * guild. Both lists are set from /menu -> Admin Actions.
 *   - Ignored: their messages aren't relayed to Discord.
 *   - Banned: ignored too, kicked from the guild, and never invited by Whisper Invite.
 *
 * A banned character's GUID is saved the first time the bot sees it, so a rename doesn't get
 * around the ban.
 *
 * The same rules (ban list, plus guildEnforcement.banned_classes and banned_races) are used
 * everywhere: Whisper Invite, a player joining the guild, and the periodic sweep.
 */
public final class Blocklist {

    private Blocklist() {}

    private static final String FILE = "data/blocklist.json";
    private static final String OLD_BANS = "data/banned_players.json";
    private static final String OLD_IGNORES = "data/ignored_players.json";

    // Don't kick the same name again for this long, whoever asks (join, sweep, /menu)
    private static final long KICK_COOLDOWN_MS = 10 * 60 * 1000L;
    // After the server refused a kick (the bot's rank is too low), wait longer
    private static final long KICK_REFUSED_COOLDOWN_MS = 60 * 60 * 1000L;

    private static final Object lock = new Object();
    // lowercase name -> GUID, or 0 until the bot has seen that character
    private static final Map<String, Long> banned = new TreeMap<>();
    private static final Set<String> ignored = new TreeSet<>();

    private static volatile Map<String, Long> bannedSnapshot = Collections.emptyMap();
    private static volatile Map<Long, String> bannedByGuid = Collections.emptyMap();
    private static volatile Set<String> ignoredSnapshot = Collections.emptySet();

    private static final Map<String, Long> nextKick = new ConcurrentHashMap<>();
    // New members whose race wasn't known when they joined
    private static final Set<Long> raceUnknown = ConcurrentHashMap.newKeySet();

    private static boolean loaded = false;

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    public static void init() {
        synchronized (lock) {
            if (loaded) return;
            loaded = true;
            Path file = Paths.get(FILE);
            if (Files.exists(file)) {
                load(file);
            } else {
                importOldFiles();
                save();
            }
            publish();
        }
        System.out.println("[Blocklist] " + banned.size() + " banned, " + ignored.size() + " ignored.");
    }

    // -------------------------------------------------------------------------
    // The lists
    // -------------------------------------------------------------------------

    public static boolean isIgnored(String name) {
        return name != null && ignoredSnapshot.contains(key(name));
    }

    public static boolean isBanned(String name) {
        return name != null && bannedSnapshot.containsKey(key(name));
    }

    /** By name, or by GUID for a banned character that was renamed. Saves the GUID the first time. */
    public static boolean isBanned(long guid, String name) {
        if (isBanned(name)) {
            if (guid != 0 && bannedSnapshot.get(key(name)) == 0L) pinGuid(name, guid);
            return true;
        }
        return guid != 0 && bannedByGuid.containsKey(guid);
    }

    public static List<String> bannedNames() {
        return new ArrayList<>(bannedSnapshot.keySet());
    }

    public static List<String> ignoredNames() {
        return new ArrayList<>(ignoredSnapshot);
    }

    public static boolean ban(String name) {
        synchronized (lock) {
            if (banned.containsKey(key(name))) return false;
            GuildMember m = GuildDataCache.findMember(name);
            banned.put(key(name), m != null ? m.guid() : 0L);
            saveAndPublish();
        }
        System.out.println("[BanList] Banned: " + name);
        return true;
    }

    public static boolean unban(String name) {
        synchronized (lock) {
            if (banned.remove(key(name)) == null) return false;
            saveAndPublish();
        }
        nextKick.remove(key(name));
        System.out.println("[BanList] Unbanned: " + name);
        return true;
    }

    public static boolean ignore(String name) {
        synchronized (lock) {
            if (!ignored.add(key(name))) return false;
            saveAndPublish();
        }
        System.out.println("[Ignore] Ignored: " + name);
        return true;
    }

    public static boolean unignore(String name) {
        synchronized (lock) {
            if (!ignored.remove(key(name))) return false;
            saveAndPublish();
        }
        System.out.println("[Ignore] Unignored: " + name);
        return true;
    }

    // -------------------------------------------------------------------------
    // The rules
    // -------------------------------------------------------------------------

    /** Why this player isn't welcome in the guild, or null if they are. */
    public static final class Reason {
        public final String kind; // "banned", "class" or "race"
        public final String text;

        Reason(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * The one rule for Whisper Invite, joins and the sweep. race may be empty while unknown,
     * then only the ban list and class are checked. The bot's own character always passes.
     */
    public static Reason check(long guid, String name, byte charClass, String race) {
        if (name == null || name.equalsIgnoreCase(botCharacter())) return null;
        if (isBanned(guid, name)) return new Reason("banned", "on the ban list");

        String cls = GameData.className(charClass).toLowerCase(Locale.ROOT);
        if (ConfigHelper.getBannedClasses().contains(cls)) return new Reason("class", "class " + cls + " is not allowed");

        String r = race == null ? "" : race.trim().toLowerCase(Locale.ROOT);
        if (!r.isEmpty()) {
            for (String bannedRace : ConfigHelper.getBannedRaces()) {
                // endsWith: Pandaren come as "Horde Pandaren" or "Alliance Pandaren"
                if (r.equals(bannedRace) || r.endsWith(" " + bannedRace)) {
                    return new Reason("race", "race " + race.trim() + " is not allowed");
                }
            }
        }
        return null;
    }

    /** Someone just joined (guild event): a banned name goes right away. */
    public static void onJoined(String name, GamePacketHandler game) {
        if (name == null || name.equalsIgnoreCase(botCharacter())) return;
        if (isBanned(name)) kick(name, "joined, but is on the ban list", game);
    }

    /** Members who weren't in the last roster: checked at once instead of at the next sweep. */
    public static void checkNewMembers(Collection<GuildMember> members, GamePacketHandler game) {
        boolean raceRules = !ConfigHelper.getBannedRaces().isEmpty();
        for (GuildMember m : members) {
            String race = GuildOnlineListPublisher.getRace(m.name());
            Reason reason = check(m.guid(), m.name(), m.charClass(), race);
            if (reason != null) {
                kick(m.name(), reason.text, game);
            } else if (raceRules && m.isOnline() && race.trim().isEmpty() && !m.name().equalsIgnoreCase(botCharacter())) {
                // Checked again when the name lookup the roster asks for online members tells
                // the bot their race. Someone who just joined is online.
                raceUnknown.add(m.guid());
            }
        }
    }

    /** A name lookup answered: finish the race check for a member who just joined. */
    public static void onRaceKnown(long guid, String name, GamePacketHandler game) {
        if (!raceUnknown.remove(guid)) return;
        GuildMember m = GuildDataCache.findMember(name);
        if (m == null) return;
        Reason reason = check(guid, name, m.charClass(), GuildOnlineListPublisher.getRace(name));
        if (reason != null) kick(name, reason.text, game);
    }

    /** The periodic check of the whole guild: catches anything the instant checks couldn't. */
    public static void sweep(Collection<GuildMember> members, GamePacketHandler game) {
        for (GuildMember m : members) {
            Reason reason = check(m.guid(), m.name(), m.charClass(), GuildOnlineListPublisher.getRace(m.name()));
            if (reason != null) kick(m.name(), reason.text, game);
        }
    }

    /** Bans a player, and kicks them right away if they're in the guild. */
    public static boolean banAndKick(String name, GamePacketHandler game) {
        if (!ban(name)) return false;
        if (game != null && GuildDataCache.findMember(name) != null) kick(name, "banned from /menu", game);
        return true;
    }

    /** The server refused a kick: wait longer before trying again, and say why once. */
    public static void onKickFailed(String name, int result) {
        if (result == 8 || result == 13) {
            nextKick.put(key(name), System.currentTimeMillis() + KICK_REFUSED_COOLDOWN_MS);
            System.err.println("[BanList] Can't kick " + name + ": check the bot's guild rank. It needs to be"
                + " allowed to remove members, and be above " + name + "'s rank. Trying again in an hour.");
        }
    }

    private static void kick(String name, String why, GamePacketHandler game) {
        long now = System.currentTimeMillis();
        Long next = nextKick.get(key(name));
        if (next != null && now < next) return;
        nextKick.put(key(name), now + KICK_COOLDOWN_MS);
        System.out.println("[BanList] Kicking " + name + ": " + why + ".");
        game.sendGuildKick(name);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** The bot's own character, from wowchat.conf. */
    static String botCharacter() {
        try {
            return wowchat.common.Global$.MODULE$.config().wow().character();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void pinGuid(String name, long guid) {
        synchronized (lock) {
            Long current = banned.get(key(name));
            if (current == null || current != 0L) return;
            banned.put(key(name), guid);
            saveAndPublish();
        }
    }

    // Call with the lock held
    private static void publish() {
        Map<Long, String> byGuid = new HashMap<>();
        for (Map.Entry<String, Long> e : banned.entrySet()) {
            if (e.getValue() != 0L) byGuid.put(e.getValue(), e.getKey());
        }
        bannedSnapshot = Collections.unmodifiableMap(new TreeMap<>(banned));
        bannedByGuid = Collections.unmodifiableMap(byGuid);
        ignoredSnapshot = Collections.unmodifiableSet(new TreeSet<>(ignored));
    }

    private static void saveAndPublish() {
        publish();
        save();
    }

    private static void save() {
        DataArray bans = DataArray.empty();
        for (Map.Entry<String, Long> e : banned.entrySet()) {
            DataObject o = DataObject.empty().put("name", e.getKey());
            if (e.getValue() != 0L) o.put("guid", e.getValue());
            bans.add(o);
        }
        DataArray ignores = DataArray.empty();
        for (String n : ignored) ignores.add(DataObject.empty().put("name", n));
        CharacterStore.writeFile(FILE, DataObject.empty()
            .put("version", 1).put("banned", bans).put("ignored", ignores).toPrettyString());
    }

    private static void load(Path file) {
        try {
            DataObject root = DataObject.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            DataArray bans = root.optArray("banned").orElse(DataArray.empty());
            for (int i = 0; i < bans.length(); i++) {
                DataObject o = bans.getObject(i);
                banned.put(key(o.getString("name")), o.getLong("guid", 0L));
            }
            DataArray ignores = root.optArray("ignored").orElse(DataArray.empty());
            for (int i = 0; i < ignores.length(); i++) {
                ignored.add(key(ignores.getObject(i).getString("name")));
            }
        } catch (Throwable t) {
            System.err.println("[Blocklist] Failed to read " + FILE + ": " + t.getMessage());
            try {
                Files.copy(file, Paths.get(FILE + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[Blocklist] Kept a copy as " + FILE + ".broken");
            } catch (Throwable ignoredError) {}
        }
    }

    // Older versions kept two files: {"banned": [names]} and {"ignored": [names]}
    private static void importOldFiles() {
        int bans = importNames(OLD_BANS, "banned", n -> banned.put(n, 0L));
        int ignores = importNames(OLD_IGNORES, "ignored", ignored::add);
        if (bans + ignores > 0) {
            System.out.println("[Blocklist] Created " + FILE + " from the old lists: " + bans + " banned, " + ignores + " ignored.");
        }
    }

    private static int importNames(String file, String field, java.util.function.Consumer<String> add) {
        Path p = Paths.get(file);
        if (!Files.exists(p)) return 0;
        int count = 0;
        try {
            DataArray arr = DataObject.fromJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                .optArray(field).orElse(DataArray.empty());
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.getString(i).trim();
                if (n.isEmpty()) continue;
                add.accept(key(n));
                count++;
            }
            Files.move(p, Paths.get(file + ".imported"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("[Blocklist] Couldn't import " + file + ": " + t.getMessage());
        }
        return count;
    }
}
