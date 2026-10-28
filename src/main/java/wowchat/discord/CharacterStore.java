package wowchat.discord;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import wowchat.game.GuildMember;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CharacterStore
 *
 * Everything the bot keeps per character, in data/characters.json, keyed by the character's
 * GUID so it follows renames:
 *   - the Discord account it's linked to (claimed in /menu, or given by an admin)
 *   - its professions
 *   - its guild rank and since when (for the promotion timers)
 *
 * Links are permanent: only Unclaim or an admin removes them. Guild data (professions, rank,
 * last seen in the guild) is dropped once the character has been out of the guild longer than
 * professions.keepHoursAfterLeaving. A record with neither is deleted.
 *
 * Writes happen under one lock. Readers get an immutable snapshot and never wait.
 */
public final class CharacterStore {

    private CharacterStore() {}

    private static final String FILE = "data/characters.json";
    private static final String OLD_PROFESSIONS = "data/professions.json";
    private static final String OLD_RANKS = "data/guild_rank_history.json";

    // "Still in the guild" updates are saved at most this often; user changes are saved at once
    private static final long SEEN_SAVE_INTERVAL_MS = 10 * 60 * 1000L;

    /** One character. Immutable: changes make a new record. */
    public static final class Record {
        public final long guid;
        public final String name;
        public final long lastSeenInGuild; // 0: never seen in the guild, or guild data dropped
        public final String discordId;     // null: not linked
        public final long linkedAt;
        public final String linkedBy;      // "self", "admin:<id>" or "import"
        public final List<String> professions; // "Herbalism:289" or "Herbalism"
        public final int rankIndex;        // -1: unknown
        public final long rankSince;

        Record(long guid, String name, long lastSeenInGuild, String discordId, long linkedAt, String linkedBy,
               List<String> professions, int rankIndex, long rankSince) {
            this.guid = guid;
            this.name = name;
            this.lastSeenInGuild = lastSeenInGuild;
            this.discordId = discordId;
            this.linkedAt = linkedAt;
            this.linkedBy = linkedBy;
            this.professions = Collections.unmodifiableList(new ArrayList<>(professions));
            this.rankIndex = rankIndex;
            this.rankSince = rankSince;
        }

        static Record empty(long guid, String name) {
            return new Record(guid, name, 0, null, 0, null, Collections.<String>emptyList(), -1, 0);
        }

        public boolean isLinked() { return discordId != null; }
        boolean hasGuildData() { return lastSeenInGuild > 0 || !professions.isEmpty() || rankIndex >= 0; }

        Record withName(String n) { return new Record(guid, n, lastSeenInGuild, discordId, linkedAt, linkedBy, professions, rankIndex, rankSince); }
        Record withSeen(long t) { return new Record(guid, name, t, discordId, linkedAt, linkedBy, professions, rankIndex, rankSince); }
        Record withLink(String id, long at, String by) { return new Record(guid, name, lastSeenInGuild, id, at, by, professions, rankIndex, rankSince); }
        Record withProfessions(List<String> p) { return new Record(guid, name, lastSeenInGuild, discordId, linkedAt, linkedBy, p, rankIndex, rankSince); }
        Record withRank(int idx, long since) { return new Record(guid, name, lastSeenInGuild, discordId, linkedAt, linkedBy, professions, idx, since); }
        Record withoutGuildData() { return new Record(guid, name, 0, discordId, linkedAt, linkedBy, Collections.<String>emptyList(), -1, 0); }
    }

    private static final class Snapshot {
        final Map<Long, Record> byGuid;
        final Map<String, Long> guidByName; // lowercase name -> the GUID last seen with that name

        Snapshot(Map<Long, Record> byGuid, Map<String, Long> guidByName) {
            this.byGuid = byGuid;
            this.guidByName = guidByName;
        }
    }

    private static final Object lock = new Object();
    private static final Map<Long, Record> records = new LinkedHashMap<>();
    // Which GUID a name belongs to now. When a new character takes a name, it moves here, so the
    // old record stops answering for that name (see Look Up Player).
    private static final Map<String, Long> nameOwner = new HashMap<>();
    private static volatile Snapshot snapshot = new Snapshot(Collections.<Long, Record>emptyMap(), Collections.<String, Long>emptyMap());

    private static boolean loaded = false;
    private static boolean needsImport = false;
    private static Set<Long> lastRoster = Collections.emptySet();
    private static boolean seenDirty = false;
    private static long lastSeenSave = 0;

    // File writes go through one background thread, in order, off the game's network thread
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CharacterStore");
        t.setDaemon(true);
        return t;
    });

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    public static void init() {
        synchronized (lock) {
            if (loaded) return;
            loaded = true;
            File f = new File(FILE);
            if (f.exists()) {
                load(f);
            } else {
                // Built from the old files and guild notes on the first guild roster
                needsImport = true;
            }
            publish();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String json;
            synchronized (lock) {
                if (!seenDirty) return;
                json = toJson();
            }
            writeFile(FILE, json);
        }));
    }

    // -------------------------------------------------------------------------
    // Reading (snapshot, no lock)
    // -------------------------------------------------------------------------

    public static Record get(long guid) {
        return snapshot.byGuid.get(guid);
    }

    /** The record that currently answers for this name, or null. */
    public static Record byName(String name) {
        if (name == null) return null;
        Snapshot s = snapshot;
        Long guid = s.guidByName.get(name.toLowerCase(Locale.ROOT));
        return guid == null ? null : s.byGuid.get(guid);
    }

    /** Discord ID linked to this guild member, or null. */
    public static String discordIdOf(GuildMember member) {
        if (member == null) return null;
        Record r = member.guid() != 0 ? get(member.guid()) : byName(member.name());
        return r == null ? null : r.discordId;
    }

    public static String discordIdOf(long guid) {
        Record r = get(guid);
        return r == null ? null : r.discordId;
    }

    /** By name only: for /who results, which carry no GUID. */
    public static String discordIdOfName(String name) {
        Record r = byName(name);
        return r == null ? null : r.discordId;
    }

    /** Every character linked to this Discord user, sorted by name. */
    public static List<Record> linkedTo(String discordId) {
        List<Record> result = new ArrayList<>();
        if (discordId == null) return result;
        for (Record r : snapshot.byGuid.values()) {
            if (discordId.equals(r.discordId)) result.add(r);
        }
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    public static List<String> professions(long guid) {
        Record r = get(guid);
        return r == null ? Collections.<String>emptyList() : r.professions;
    }

    /** Characters with this profession: [name, stored value], sorted by name. */
    public static List<String[]> withProfession(String profession) {
        List<String[]> result = new ArrayList<>();
        for (Record r : snapshot.byGuid.values()) {
            for (String stored : r.professions) {
                if (ProfessionManager.profName(stored).equalsIgnoreCase(profession)) {
                    result.add(new String[]{ r.name, stored });
                }
            }
        }
        result.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return result;
    }

    /** Full hours this character has been at this rank, or -1 if not tracked. */
    public static long hoursAtRank(long guid, int rankIndex) {
        Record r = get(guid);
        if (r == null || r.rankIndex != rankIndex) return -1;
        return (System.currentTimeMillis() - r.rankSince) / (1000L * 60 * 60);
    }

    // -------------------------------------------------------------------------
    // Links
    // -------------------------------------------------------------------------

    public enum LinkResult { LINKED, ALREADY_YOURS, LINKED_TO_SOMEONE_ELSE }

    /** Links a character to a Discord user, unless someone else already has it. */
    public static LinkResult link(long guid, String name, String discordId, String linkedBy) {
        synchronized (lock) {
            Record r = records.get(guid);
            if (r != null && r.isLinked()) {
                return discordId.equals(r.discordId) ? LinkResult.ALREADY_YOURS : LinkResult.LINKED_TO_SOMEONE_ELSE;
            }
            if (r == null) r = Record.empty(guid, name);
            put(r.withName(name).withLink(discordId, System.currentTimeMillis(), linkedBy));
            saveNow();
        }
        System.out.println("[Characters] " + name + " linked to Discord user " + discordId + " (" + linkedBy + ")");
        return LinkResult.LINKED;
    }

    /** Moves a character to a Discord user, replacing any link (admin Give to Member). */
    public static void setLink(long guid, String name, String discordId, String linkedBy) {
        String before;
        synchronized (lock) {
            Record r = records.get(guid);
            if (r == null) r = Record.empty(guid, name);
            before = r.discordId;
            put(r.withName(name).withLink(discordId, System.currentTimeMillis(), linkedBy));
            saveNow();
        }
        System.out.println("[Characters] " + name + " given to Discord user " + discordId + " by " + linkedBy
            + (before != null ? " (was " + before + ")" : ""));
    }

    /** Removes a link. False if the character wasn't linked. */
    public static boolean unlink(long guid, String by) {
        String name;
        synchronized (lock) {
            Record r = records.get(guid);
            if (r == null || !r.isLinked()) return false;
            name = r.name;
            Record updated = r.withLink(null, 0, null);
            if (updated.hasGuildData()) put(updated); else remove(guid);
            saveNow();
        }
        System.out.println("[Characters] " + name + " unlinked by " + by);
        return true;
    }

    // -------------------------------------------------------------------------
    // Professions
    // -------------------------------------------------------------------------

    public static void setProfessions(long guid, String name, List<String> professions) {
        synchronized (lock) {
            Record r = records.get(guid);
            if (r == null) r = Record.empty(guid, name).withSeen(System.currentTimeMillis());
            List<String> sorted = new ArrayList<>(professions);
            Collections.sort(sorted);
            Record updated = r.withName(name).withProfessions(sorted);
            if (updated.isLinked() || updated.hasGuildData()) put(updated); else remove(guid);
            saveNow();
        }
    }

    // -------------------------------------------------------------------------
    // What the game tells us
    // -------------------------------------------------------------------------

    /**
     * Called with every guild roster (about once a minute). Follows renames, keeps "last seen in
     * the guild" and rank timers up to date, and drops the guild data of characters gone longer
     * than professions.keepHoursAfterLeaving.
     */
    public static void onRoster(Collection<GuildMember> members) {
        if (members == null || members.isEmpty()) return;
        long now = System.currentTimeMillis();
        long keepMs = (long) (ConfigHelper.getProfessionKeepHours() * 60 * 60 * 1000);
        boolean changed = false;

        synchronized (lock) {
            if (needsImport) {
                importOldData(members, now);
                needsImport = false;
                changed = true;
            }

            Set<Long> current = new HashSet<>();
            for (GuildMember m : members) {
                if (m.guid() == 0) continue;
                current.add(m.guid());
                Record r = records.get(m.guid());
                if (r == null) {
                    r = Record.empty(m.guid(), m.name());
                    changed = true;
                }
                if (!r.name.equals(m.name())) {
                    System.out.println("[Characters] " + r.name + " is now named " + m.name());
                    r = r.withName(m.name());
                    changed = true;
                }
                // A new rank, a first sighting, or a return to the guild starts the timer again
                boolean rejoined = !lastRoster.isEmpty() && !lastRoster.contains(m.guid());
                if (r.rankIndex != m.rankIndex() || rejoined) {
                    r = r.withRank(m.rankIndex(), now);
                    changed = true;
                }
                r = r.withSeen(now);
                put(r);
            }

            // Gone from the guild: keep guild data for the grace period, links always
            for (Record r : new ArrayList<>(records.values())) {
                if (current.contains(r.guid) || !r.hasGuildData()) continue;
                if (now - r.lastSeenInGuild > keepMs) {
                    Record updated = r.withoutGuildData();
                    if (updated.isLinked()) put(updated); else remove(r.guid);
                    changed = true;
                }
            }
            lastRoster = current;

            if (changed) {
                saveNow();
            } else {
                seenDirty = true;
                if (now - lastSeenSave > SEEN_SAVE_INTERVAL_MS) saveNow();
            }
        }
    }

    /**
     * A character the bot saw with this name (chat, whisper, name lookup). Follows renames of
     * known characters, and moves the name to this GUID if another character used to have it.
     */
    public static void onSeen(long guid, String name) {
        if (guid == 0 || name == null || name.isEmpty()) return;
        Snapshot s = snapshot;
        Record r = s.byGuid.get(guid);
        Long owner = s.guidByName.get(name.toLowerCase(Locale.ROOT));
        boolean renamed = r != null && !r.name.equals(name);
        boolean nameMoved = owner != null && owner != guid;
        if (!renamed && !nameMoved) return;

        synchronized (lock) {
            Record current = records.get(guid);
            if (current != null && !current.name.equals(name)) {
                System.out.println("[Characters] " + current.name + " is now named " + name);
                put(current.withName(name));
                saveNow();
            } else if (current == null) {
                // Not one of ours: just stop the old record answering for this name
                nameOwner.put(name.toLowerCase(Locale.ROOT), guid);
                publish();
            }
        }
    }

    /**
     * The server says no character has this GUID: it was deleted. GUIDs are never reused, so its
     * record (link included) is removed, and a new character with the same name starts clean.
     */
    public static void onNameUnknown(long guid) {
        Record r = snapshot.byGuid.get(guid);
        if (r == null) return;
        synchronized (lock) {
            // Still in the guild means it exists: never drop it on a stray answer
            if (lastRoster.contains(guid) || !records.containsKey(guid)) return;
            remove(guid);
            saveNow();
        }
        System.out.println("[Characters] " + r.name + " was deleted"
            + (r.isLinked() ? ": its link to Discord user " + r.discordId + " is removed" : ""));
    }

    // Linked characters outside the guild are checked once after startup, then every hour
    private static final long LINK_CHECK_INTERVAL_MS = 60 * 60 * 1000L;
    private static long lastLinkCheck = 0;

    /**
     * Linked characters outside the guild whose GUID the bot should ask the server about now
     * (empty until it's time again). Answers come back through onSeen (renamed) or
     * onNameUnknown (deleted). Guild members don't need it: the roster shows them.
     */
    public static List<Long> linksToCheck() {
        long now = System.currentTimeMillis();
        List<Long> result = new ArrayList<>();
        synchronized (lock) {
            if (lastRoster.isEmpty() || now - lastLinkCheck < LINK_CHECK_INTERVAL_MS) return result;
            lastLinkCheck = now;
            for (Record r : records.values()) {
                if (r.isLinked() && !lastRoster.contains(r.guid)) result.add(r.guid);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internals (call with the lock held)
    // -------------------------------------------------------------------------

    private static void put(Record r) {
        Record old = records.put(r.guid, r);
        if (old != null && !old.name.equalsIgnoreCase(r.name)) {
            String oldKey = old.name.toLowerCase(Locale.ROOT);
            if (Long.valueOf(r.guid).equals(nameOwner.get(oldKey))) nameOwner.remove(oldKey);
        }
        nameOwner.put(r.name.toLowerCase(Locale.ROOT), r.guid);
    }

    private static void remove(long guid) {
        Record old = records.remove(guid);
        if (old != null) {
            String key = old.name.toLowerCase(Locale.ROOT);
            if (Long.valueOf(guid).equals(nameOwner.get(key))) nameOwner.remove(key);
        }
    }

    private static void publish() {
        snapshot = new Snapshot(
            Collections.unmodifiableMap(new LinkedHashMap<>(records)),
            Collections.unmodifiableMap(new HashMap<>(nameOwner)));
    }

    private static void saveNow() {
        publish();
        seenDirty = false;
        lastSeenSave = System.currentTimeMillis();
        String json = toJson();
        writer.submit(() -> writeFile(FILE, json));
    }

    private static String toJson() {
        DataObject chars = DataObject.empty();
        for (Record r : records.values()) {
            DataObject o = DataObject.empty().put("name", r.name);
            if (r.lastSeenInGuild > 0) o.put("lastSeenInGuild", r.lastSeenInGuild);
            if (r.discordId != null) {
                o.put("discordId", r.discordId).put("linkedAt", r.linkedAt).put("linkedBy", r.linkedBy);
            }
            if (!r.professions.isEmpty()) o.put("professions", DataArray.fromCollection(r.professions));
            if (r.rankIndex >= 0) o.put("rank", DataObject.empty().put("index", r.rankIndex).put("since", r.rankSince));
            chars.put(String.valueOf(r.guid), o);
        }
        return DataObject.empty().put("characters", chars).toPrettyString();
    }

    // Written to a temporary file first, then swapped in, so a crash can't leave half a file.
    // Also used for blocklist.json.
    static void writeFile(String file, String json) {
        try {
            Path target = Paths.get(file);
            Files.createDirectories(target.getParent());
            Path tmp = Paths.get(file + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            System.err.println("[Data] Failed to save " + file + ": " + t.getMessage());
        }
    }

    private static void load(File f) {
        try {
            DataObject root = DataObject.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            DataObject chars = root.optObject("characters").orElse(DataObject.empty());
            for (String key : chars.keys()) {
                DataObject o = chars.getObject(key);
                long guid = Long.parseLong(key);
                List<String> profs = new ArrayList<>();
                DataArray arr = o.optArray("professions").orElse(DataArray.empty());
                for (int i = 0; i < arr.length(); i++) profs.add(arr.getString(i));
                DataObject rank = o.optObject("rank").orElse(null);
                put(new Record(guid,
                    o.getString("name"),
                    o.getLong("lastSeenInGuild", 0),
                    o.getString("discordId", null),
                    o.getLong("linkedAt", 0),
                    o.getString("linkedBy", null),
                    profs,
                    rank == null ? -1 : rank.getInt("index"),
                    rank == null ? 0 : rank.getLong("since")));
            }
            System.out.println("[Characters] Loaded " + records.size() + " characters.");
        } catch (Throwable t) {
            // Never overwrite a file we couldn't read: keep a copy before anything is saved
            System.err.println("[Characters] Failed to read " + FILE + ": " + t.getMessage());
            try {
                Files.copy(f.toPath(), Paths.get(FILE + ".broken"), StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[Characters] Kept a copy as " + FILE + ".broken");
            } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // One-time import: professions.json, guild_rank_history.json and Discord IDs in guild notes
    // -------------------------------------------------------------------------

    // Older versions linked characters by a Discord User ID in the guild note
    private static final Pattern NOTE_DISCORD_ID = Pattern.compile("(?:Discord:\\s*)?(\\d{17,19})(?:\\s|$)", Pattern.CASE_INSENSITIVE);

    private static void importOldData(Collection<GuildMember> members, long now) {
        Map<String, GuildMember> byName = new HashMap<>();
        for (GuildMember m : members) {
            if (m.guid() != 0) byName.put(m.name().toLowerCase(Locale.ROOT), m);
        }

        int links = 0, profs = 0, ranks = 0, dropped = 0;

        String noteLocation = ConfigHelper.getDiscordLinkingNoteLocation();
        for (GuildMember m : byName.values()) {
            String note = "public".equals(noteLocation) ? m.publicNote() : m.officerNote();
            Matcher matcher = NOTE_DISCORD_ID.matcher(note == null ? "" : note.trim());
            if (!matcher.find()) continue;
            Record r = records.containsKey(m.guid()) ? records.get(m.guid()) : Record.empty(m.guid(), m.name());
            put(r.withLink(matcher.group(1), now, "import"));
            links++;
        }

        DataObject oldProfs = readOld(OLD_PROFESSIONS);
        if (oldProfs != null) {
            for (String name : oldProfs.keys()) {
                GuildMember m = byName.get(name.toLowerCase(Locale.ROOT));
                if (m == null) { dropped++; continue; }
                Object value = oldProfs.toMap().get(name);
                List<String> list = new ArrayList<>();
                DataArray arr = value instanceof Map
                    ? oldProfs.getObject(name).optArray("professions").orElse(DataArray.empty())
                    : oldProfs.getArray(name);
                for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
                if (list.isEmpty()) continue;
                Collections.sort(list);
                Record r = records.containsKey(m.guid()) ? records.get(m.guid()) : Record.empty(m.guid(), m.name());
                put(r.withProfessions(list).withSeen(now));
                profs++;
            }
        }

        DataObject oldRanks = readOld(OLD_RANKS);
        if (oldRanks != null) {
            for (String name : oldRanks.keys()) {
                GuildMember m = byName.get(name.toLowerCase(Locale.ROOT));
                if (m == null) continue;
                DataObject o = oldRanks.getObject(name);
                Record r = records.containsKey(m.guid()) ? records.get(m.guid()) : Record.empty(m.guid(), m.name());
                put(r.withRank(o.getInt("rank"), o.getLong("since")).withSeen(now));
                ranks++;
            }
        }

        // The old files stay, renamed, in case something went wrong
        for (String old : Arrays.asList(OLD_PROFESSIONS, OLD_RANKS)) {
            try {
                Path p = Paths.get(old);
                if (Files.exists(p)) Files.move(p, Paths.get(old + ".imported"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                System.err.println("[Characters] Couldn't rename " + old + ": " + t.getMessage());
            }
        }

        System.out.println("[Characters] Created " + FILE + ": imported " + links + " links from guild notes, "
            + profs + " characters' professions and " + ranks + " rank timers"
            + (dropped > 0 ? " (" + dropped + " professions skipped: those characters aren't in the guild)" : "") + ".");
    }

    private static DataObject readOld(String file) {
        try {
            Path p = Paths.get(file);
            if (!Files.exists(p)) return null;
            return DataObject.fromJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            System.err.println("[Characters] Couldn't read " + file + " to import it: " + t.getMessage());
            return null;
        }
    }
}
