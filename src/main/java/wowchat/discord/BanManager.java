package wowchat.discord;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * BanManager
 *
 * Ban list - mirrors IgnoreManager pattern but in Java, stored in data/.
 * Banned players are silenced in Discord and auto-kicked from the guild each cycle.
 */
public final class BanManager {

    private BanManager() {}

    private static final String BAN_FILE = "data/banned_players.json";
    private static final Set<String> bannedPlayers = new TreeSet<>(); // TreeSet = always sorted

    public static void init() {
        new File("data").mkdirs();
        File f = new File(BAN_FILE);
        if (!f.exists()) {
            save();
            return;
        }
        try {
            String content = new String(Files.readAllBytes(Paths.get(BAN_FILE)), "UTF-8");
            // Parse {"banned": ["name1", "name2"]}
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"banned\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL)
                .matcher(content);
            if (m.find()) {
                String arr = m.group(1);
                for (String part : arr.split(",")) {
                    String name = part.trim().replaceAll("\"", "");
                    if (!name.isEmpty()) bannedPlayers.add(name.toLowerCase());
                }
            }
            System.out.println("[BanList] Loaded " + bannedPlayers.size() + " banned players.");
        } catch (Throwable t) {
            System.err.println("[BanList] Failed to load ban list: " + t.getMessage());
        }
    }

    public static synchronized boolean ban(String playerName) {
        String normalized = playerName.toLowerCase();
        if (bannedPlayers.contains(normalized)) return false;
        bannedPlayers.add(normalized);
        save();
        System.out.println("[BanList] Banned: " + playerName);
        return true;
    }

    public static synchronized boolean unban(String playerName) {
        String normalized = playerName.toLowerCase();
        if (!bannedPlayers.contains(normalized)) return false;
        bannedPlayers.remove(normalized);
        save();
        System.out.println("[BanList] Unbanned: " + playerName);
        return true;
    }

    public static synchronized boolean isBanned(String playerName) {
        return bannedPlayers.contains(playerName.toLowerCase());
    }

    public static synchronized List<String> getBannedPlayers() {
        return new ArrayList<>(bannedPlayers); // already sorted by TreeSet
    }

    private static void save() {
        try {
            new File("data").mkdirs();
            StringBuilder sb = new StringBuilder("{\"banned\": [");
            boolean first = true;
            for (String name : bannedPlayers) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(name).append("\"");
            }
            sb.append("]}");
            try (PrintWriter pw = new PrintWriter(new File(BAN_FILE), "UTF-8")) {
                pw.println(sb.toString());
            }
        } catch (Throwable t) {
            System.err.println("[BanList] Failed to save ban list: " + t.getMessage());
        }
    }
}
