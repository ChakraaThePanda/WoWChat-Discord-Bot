package wowchat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * WatchdogMain - Manages wowchat.jar as a child process.
 *
 * Monitors two health files written by the bot:
 *
 *   wow.health     - written every 30s while the bot is running.
 *                    If stale: the bot is stuck. Restart.
 *
 *   discord.health - written every 30s while the bot is running.
 *                    If stale while wow.health is fresh: Discord is stuck. Restart.
 *
 * The bot's internal WoW reconnect loop handles normal server bounces on its own.
 * The watchdog only intervenes when the bot is truly stuck or silently broken.
 *
 * Built automatically by mvn package alongside wowchat.jar.
 * Run (from the bot folder): java -jar lib/watchdog.jar [--log data/bot.log] lib/wowchat.jar wowchat.conf
 *
 * With --log, everything the bot and the watchdog print goes to that file. Past 5 MB it's
 * copied to bot.old.log (replacing the previous one) and emptied, so the log never takes more
 * than about 10 MB. Copied and emptied rather than renamed: Windows won't rename a file while
 * the tray's log window has it open.
 */
public final class WatchdogMain {

    private static final String WOW_HEALTH_FILE         = "data/wow.health";
    private static final String DISCORD_HEALTH_FILE     = "data/discord.health";

    private static final int    STARTUP_TIMEOUT_SECONDS = 120;
    private static final int    WOW_STALE_SECONDS       = 180;  // 3 min
    private static final int    DISCORD_STALE_SECONDS   = 600;  // 10 min
    private static final int    RESTART_DELAY_SECONDS   = 15;
    private static final int    CHECK_INTERVAL_MS       = 10000;
    private static final int    SETUP_ERROR_EXIT_CODE   = 2;    // WoWChat.SETUP_ERROR_EXIT_CODE

    private static final long   LOG_MAX_BYTES           = 5L * 1024 * 1024;

    private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        if (rest.size() >= 2 && rest.get(0).equals("--log")) {
            PrintStream log = new PrintStream(new RollingLog(new File(rest.get(1)), LOG_MAX_BYTES), true, "UTF-8");
            System.setOut(log);
            System.setErr(log);
            rest = rest.subList(2, rest.size());
        }
        if (rest.isEmpty()) {
            System.err.println("[Watchdog] Usage: java -jar watchdog.jar [--log data/bot.log] wowchat.jar [wowchat.conf]");
            System.exit(1);
        }

        String jarFile   = rest.get(0);
        String[] botArgs = rest.subList(1, rest.size()).toArray(new String[0]);

        System.out.println("[Watchdog] Starting. Managing: " + jarFile);

        //noinspection InfiniteLoopStatement
        while (true) {
            try { Files.deleteIfExists(Paths.get(WOW_HEALTH_FILE)); } catch (Throwable ignored) {}
            try { Files.deleteIfExists(Paths.get(DISCORD_HEALTH_FILE)); } catch (Throwable ignored) {}

            Process process = startProcess(jarFile, botArgs);
            System.out.println("[Watchdog] Bot process started.");

            pump(process.getInputStream(), false);
            pump(process.getErrorStream(), true);

            long    startTime     = System.currentTimeMillis();
            boolean shouldRestart = false;
            String  restartReason = "";

            while (true) {
                try {
                    int code = process.exitValue();
                    restartReason = "Bot process exited with code " + code;
                    shouldRestart = true;
                    break;
                } catch (IllegalThreadStateException e) {
                    // Still running
                }

                long now    = System.currentTimeMillis();
                long uptime = (now - startTime) / 1000L;
                File wowHf  = new File(WOW_HEALTH_FILE);

                if (!wowHf.exists()) {
                    if (uptime > STARTUP_TIMEOUT_SECONDS) {
                        restartReason = "wow.health never created after " + uptime + "s - bot stuck at startup";
                        shouldRestart = true;
                        break;
                    }
                    Thread.sleep(CHECK_INTERVAL_MS);
                    continue;
                }

                long wowTimestamp = readTimestamp(WOW_HEALTH_FILE);
                long wowStaleBy   = (now - wowTimestamp) / 1000L;

                if (wowStaleBy > WOW_STALE_SECONDS) {
                    restartReason = "wow.health stale by " + wowStaleBy + "s - WoW connection dead";
                    shouldRestart = true;
                    break;
                }

                File discordHf = new File(DISCORD_HEALTH_FILE);
                if (discordHf.exists()) {
                    long discordTimestamp = readTimestamp(DISCORD_HEALTH_FILE);
                    long discordStaleBy   = (now - discordTimestamp) / 1000L;
                    if (discordStaleBy > DISCORD_STALE_SECONDS && wowStaleBy < 60) {
                        restartReason = "discord.health stale by " + discordStaleBy + "s while WoW is active - Discord relay broken";
                        shouldRestart = true;
                        break;
                    }
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            }

            if (restartReason.equals("Bot process exited with code " + SETUP_ERROR_EXIT_CODE)) {
                System.out.println("[Watchdog] Not restarting: the bot stopped because of a setup problem (see above).");
                System.exit(SETUP_ERROR_EXIT_CODE);
            }

            if (shouldRestart) {
                System.out.println("[Watchdog] Restarting. Reason: " + restartReason);
                process.destroy();
                Thread.sleep(RESTART_DELAY_SECONDS * 1000L);
            }
        }
    }

    private static long readTimestamp(String path) {
        try {
            String raw = new String(Files.readAllBytes(Paths.get(path)), "UTF-8").trim();
            
            // Handle empty file
            if (raw.isEmpty()) {
                String timestamp = timestampFormat.format(new Date());
                System.err.println("[Watchdog] " + timestamp + " - Could not read " + path + ": File is empty");
                return System.currentTimeMillis();
            }
            
            return Long.parseLong(raw);
        } catch (NumberFormatException nfe) {
            String timestamp = timestampFormat.format(new Date());
            System.err.println("[Watchdog] " + timestamp + " - Could not read " + path + ": Invalid number format: \"" + nfe.getMessage() + "\"");
            return System.currentTimeMillis();
        } catch (Throwable t) {
            String timestamp = timestampFormat.format(new Date());
            System.err.println("[Watchdog] " + timestamp + " - Could not read " + path + ": " + t.getMessage());
            return System.currentTimeMillis();
        }
    }

    private static Process startProcess(String jarFile, String[] args) throws Exception {
        // Same Java as the watchdog, so a freshly installed Java works before it's on PATH
        File javaExe = new File(System.getProperty("java.home"), "bin" + File.separator + "java");
        // logback.xml ships next to wowchat.jar
        File jarDir = new File(jarFile).getAbsoluteFile().getParentFile();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.getPath());
        cmd.add("-XX:+HeapDumpOnOutOfMemoryError");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dstderr.encoding=UTF-8");
        cmd.add("-Dlogback.configurationFile=" + new File(jarDir, "logback.xml").getPath());
        cmd.add("-jar");
        cmd.add(jarFile);
        for (String a : args) cmd.add(a);
        return new ProcessBuilder(cmd)
            .directory(new File("."))
            .start();
    }

    // A log file that starts over past maxBytes, keeping the previous part as <name>.old.log
    private static final class RollingLog extends OutputStream {
        private final File file;
        private final File old;
        private final long maxBytes;
        private FileOutputStream out;
        private long size;

        RollingLog(File file, long maxBytes) throws IOException {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            String name = file.getName();
            String base = name.endsWith(".log") ? name.substring(0, name.length() - 4) : name;
            this.file = file;
            this.old = new File(dir, base + ".old.log");
            this.maxBytes = maxBytes;
            this.out = new FileOutputStream(file, true);
            this.size = file.length();
        }

        @Override
        public synchronized void write(int b) throws IOException {
            write(new byte[]{ (byte) b }, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            size += len;
            if (size > maxBytes) roll();
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }

        private void roll() throws IOException {
            out.close();
            try {
                Files.copy(file.toPath(), old.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // The old part couldn't be kept (file in use): start over anyway
            }
            out = new FileOutputStream(file, false);
            size = 0;
        }
    }

    private static void pump(final InputStream in, final boolean isErr) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (isErr) System.err.println(line);
                    else       System.out.println(line);
                }
            } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }
}
