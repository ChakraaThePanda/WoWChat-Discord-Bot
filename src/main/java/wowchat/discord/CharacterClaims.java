package wowchat.discord;

import wowchat.game.GamePacketHandler;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CharacterClaims
 *
 * Linking a character to a Discord account, proven in game:
 *   1. In /menu -> My Characters -> Claim, the member types the character's name and gets a code.
 *   2. They whisper the code to the bot from that character. The server says who sent a whisper,
 *      so only someone who can log into the character can do this.
 *   3. The bot whispers back which Discord account the character will be linked to, and the
 *      player confirms with "yes" (or "no"). Seeing the account name stops anyone from tricking
 *      a player into whispering someone else's code.
 *
 * Codes and their yes/no are never relayed to Discord. Pending claims are only kept in memory:
 * a restart drops them, and the member starts again.
 */
public final class CharacterClaims {

    private CharacterClaims() {}

    // Under Discord's 15 minute limit for updating the member's /menu message
    private static final long CODE_LIFETIME_MS = 10 * 60 * 1000L;
    private static final long CONFIRM_LIFETIME_MS = 2 * 60 * 1000L;
    private static final int MAX_STARTS = 5;                  // claims started per user...
    private static final long STARTS_WINDOW_MS = 10 * 60 * 1000L; // ...in this window
    private static final int MAX_WRONG_CODES = 5;            // per character, before a pause
    private static final long WRONG_CODE_PAUSE_MS = 10 * 60 * 1000L;
    private static final int MAX_CHARACTERS = 50;            // links per Discord user, as a sanity limit

    // "4821" or "link 4821", with the "{?" some servers put in front
    private static final Pattern CODE = Pattern.compile("^(?:link\\s+)?(\\d{4})$", Pattern.CASE_INSENSITIVE);

    private static final class Pending {
        final int code;
        final String userId;
        final String userName;   // the Discord username (unique, unlike display names)
        final String serverName;
        final String character;  // as typed, capitalized
        final long createdAt;
        final Consumer<String> status; // updates the member's /menu message
        long confirmGuid;        // set once the code came from the right character
        long confirmUntil;

        Pending(int code, String userId, String userName, String serverName, String character, Consumer<String> status) {
            this.code = code;
            this.userId = userId;
            this.userName = userName;
            this.serverName = serverName;
            this.character = character;
            this.createdAt = System.currentTimeMillis();
            this.status = status;
        }
    }

    private static final Object lock = new Object();
    private static final Map<Integer, Pending> byCode = new HashMap<>();
    private static final Map<String, Integer> codeByUser = new HashMap<>();
    private static final Map<String, Deque<Long>> startsByUser = new HashMap<>();
    private static final Map<Long, int[]> wrongCodes = new HashMap<>();   // guid -> {count}
    private static final Map<Long, Long> pausedUntil = new HashMap<>();
    private static final SecureRandom random = new SecureRandom();

    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CharacterClaims");
        t.setDaemon(true);
        return t;
    });

    /** What starting a claim gave: a code, or a reason it can't start. */
    public static final class Start {
        public final int code;       // 0 when refused
        public final String problem; // null when started

        Start(int code, String problem) {
            this.code = code;
            this.problem = problem;
        }
    }

    // -------------------------------------------------------------------------
    // Discord side
    // -------------------------------------------------------------------------

    /**
     * Starts a claim for this Discord user. status gets short updates for their /menu message
     * (code received, linked, expired...).
     */
    public static Start start(String userId, String userName, String serverName, String character, Consumer<String> status) {
        if (!character.matches("\\p{L}{2,12}")) {
            return new Start(0, "❌ **" + character + "** isn't a valid character name.");
        }
        if (character.equalsIgnoreCase(Blocklist.botCharacter())) {
            return new Start(0, "❌ That's the bot's own character.");
        }
        if (Blocklist.isBanned(character)) {
            return new Start(0, "❌ **" + character + "** is on the ban list.");
        }
        // Refused early only when the guild roster proves which character this name is. Outside
        // the guild the name may belong to a new character now: the whisper's GUID decides.
        wowchat.game.GuildMember member = GuildDataCache.findMember(character);
        CharacterStore.Record existing = member != null ? CharacterStore.get(member.guid()) : null;
        if (existing != null && existing.isLinked()) {
            return new Start(0, userId.equals(existing.discordId)
                ? "ℹ️ **" + existing.name + "** is already linked to you."
                : "❌ **" + existing.name + "** is already linked to someone else. Ask an admin to move it to you.");
        }
        if (CharacterStore.linkedTo(userId).size() >= MAX_CHARACTERS) {
            return new Start(0, "❌ You already have " + MAX_CHARACTERS + " characters linked.");
        }

        Pending pending;
        synchronized (lock) {
            cleanUp();
            long now = System.currentTimeMillis();
            Deque<Long> starts = startsByUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
            while (!starts.isEmpty() && now - starts.peekFirst() > STARTS_WINDOW_MS) starts.pollFirst();
            if (starts.size() >= MAX_STARTS) {
                return new Start(0, "❌ Too many claims in a short time. Try again in a few minutes.");
            }
            starts.addLast(now);

            // One claim at a time per member: a new one replaces the old one
            Integer old = codeByUser.remove(userId);
            if (old != null) byCode.remove(old);

            int code;
            do {
                code = 1000 + random.nextInt(9000);
            } while (byCode.containsKey(code));
            pending = new Pending(code, userId, userName, serverName, character, status);
            byCode.put(code, pending);
            codeByUser.put(userId, code);
        }

        final Pending p = pending;
        timer.schedule(() -> expire(p), CODE_LIFETIME_MS, TimeUnit.MILLISECONDS);
        System.out.println("[Characters] " + userName + " started a claim for " + character);
        return new Start(pending.code, null);
    }

    // -------------------------------------------------------------------------
    // Game side
    // -------------------------------------------------------------------------

    /**
     * Every whisper to the bot comes here first. True when it was part of a claim (a code, or
     * yes/no to a confirmation): those are never relayed to Discord.
     */
    public static boolean handleWhisper(long guid, String sender, String message, GamePacketHandler game) {
        String text = message == null ? "" : message.trim();
        if (text.startsWith("{?")) text = text.substring(2).trim();

        if (text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("no")) {
            return handleAnswer(guid, sender, text.equalsIgnoreCase("yes"), game);
        }

        Matcher m = CODE.matcher(text);
        if (!m.matches()) return false;
        handleCode(guid, sender, Integer.parseInt(m.group(1)), game);
        return true;
    }

    private static void handleCode(long guid, String sender, int code, GamePacketHandler game) {
        String reply;
        Pending pending;
        synchronized (lock) {
            cleanUp();
            long now = System.currentTimeMillis();
            Long paused = pausedUntil.get(guid);
            if (paused != null && now < paused) return; // too many wrong codes: ignored for a while

            pending = byCode.get(code);
            if (pending == null) {
                int[] wrong = wrongCodes.computeIfAbsent(guid, k -> new int[1]);
                if (++wrong[0] >= MAX_WRONG_CODES) {
                    pausedUntil.put(guid, now + WRONG_CODE_PAUSE_MS);
                    wrongCodes.remove(guid);
                    reply = "Too many wrong codes. Wait 10 minutes, then start again from /menu in Discord.";
                } else {
                    reply = "That code isn't valid or has expired. Get a new one from /menu -> My Characters -> Claim in Discord.";
                }
                pending = null;
            } else if (!pending.character.equalsIgnoreCase(sender)) {
                // A real code from the wrong character: refused, and the code stays valid
                reply = "This code is for " + pending.character + ". Whisper it from that character.";
                pending = null;
            } else {
                wrongCodes.remove(guid);
                pending.confirmGuid = guid;
                pending.confirmUntil = now + CONFIRM_LIFETIME_MS;
                reply = null;
            }
        }

        if (pending == null) {
            game.sendWhisper(sender, reply);
            return;
        }

        CharacterStore.Record r = CharacterStore.get(guid);
        if (r != null && r.isLinked()) {
            drop(pending);
            boolean mine = pending.userId.equals(r.discordId);
            game.sendWhisper(sender, mine
                ? sender + " is already linked to your Discord account."
                : sender + " is already linked to another Discord account. Ask an admin in Discord.");
            pending.status.accept(mine
                ? "ℹ️ **" + sender + "** is already linked to you."
                : "❌ **" + sender + "** is already linked to someone else. Ask an admin to move it to you.");
            return;
        }

        game.sendWhisper(sender, "This will link " + sender + " to the Discord account " + pending.userName
            + " on " + pending.serverName + ". Whisper yes to confirm, or no to cancel.");
        pending.status.accept("📨 Code received from **" + sender + "**. Whisper `yes` to the bot in game to confirm.");
        final Pending p = pending;
        timer.schedule(() -> confirmExpired(p), CONFIRM_LIFETIME_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean handleAnswer(long guid, String sender, boolean yes, GamePacketHandler game) {
        Pending pending = null;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Pending p : byCode.values()) {
                if (p.confirmGuid == guid && now < p.confirmUntil) {
                    pending = p;
                    break;
                }
            }
            if (pending == null) return false; // a normal "yes" whisper: relayed as usual
        }
        drop(pending);

        if (!yes) {
            game.sendWhisper(sender, "Cancelled. " + sender + " was not linked.");
            pending.status.accept("❌ Cancelled in game. **" + sender + "** was not linked.");
            System.out.println("[Characters] Claim of " + sender + " by " + pending.userName + " cancelled in game");
            return true;
        }

        CharacterStore.LinkResult result = CharacterStore.link(guid, sender, pending.userId, "self");
        switch (result) {
            case LINKED:
                game.sendWhisper(sender, sender + " is now linked to " + pending.userName + " on " + pending.serverName + ".");
                pending.status.accept("✅ **" + sender + "** is now linked to your Discord account.");
                // Anyone else waiting to claim this character can't anymore
                dropOtherClaims(sender, pending);
                break;
            case ALREADY_YOURS:
                game.sendWhisper(sender, sender + " is already linked to your Discord account.");
                pending.status.accept("ℹ️ **" + sender + "** is already linked to you.");
                break;
            default:
                game.sendWhisper(sender, sender + " is already linked to another Discord account. Ask an admin in Discord.");
                pending.status.accept("❌ **" + sender + "** is already linked to someone else. Ask an admin to move it to you.");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static void drop(Pending p) {
        synchronized (lock) {
            if (byCode.get(p.code) == p) byCode.remove(p.code);
            if (Integer.valueOf(p.code).equals(codeByUser.get(p.userId))) codeByUser.remove(p.userId);
        }
    }

    private static void dropOtherClaims(String character, Pending winner) {
        List<Pending> others = new ArrayList<>();
        synchronized (lock) {
            for (Pending p : byCode.values()) {
                if (p != winner && p.character.equalsIgnoreCase(character)) others.add(p);
            }
        }
        for (Pending p : others) {
            drop(p);
            p.status.accept("❌ **" + character + "** was just linked to someone else. Ask an admin if it's yours.");
        }
    }

    private static void expire(Pending p) {
        synchronized (lock) {
            if (byCode.get(p.code) != p) return;
            // Waiting for yes/no: confirmExpired takes care of it
            if (p.confirmGuid != 0 && System.currentTimeMillis() < p.confirmUntil) return;
        }
        drop(p);
        p.status.accept("⌛ The code for **" + p.character + "** expired. Press **Claim** to get a new one.");
    }

    private static void confirmExpired(Pending p) {
        synchronized (lock) {
            if (byCode.get(p.code) != p || System.currentTimeMillis() < p.confirmUntil) return;
        }
        drop(p);
        p.status.accept("⌛ No answer in game, so **" + p.character + "** was not linked. Press **Claim** to try again.");
    }

    // Call with the lock held
    private static void cleanUp() {
        long now = System.currentTimeMillis();
        byCode.values().removeIf(p -> now - p.createdAt > CODE_LIFETIME_MS && !(p.confirmGuid != 0 && now < p.confirmUntil));
        codeByUser.values().removeIf(code -> !byCode.containsKey(code));
        pausedUntil.values().removeIf(until -> now >= until);
    }
}
