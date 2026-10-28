package wowchat.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import scala.Option;
import wowchat.commands.WhoRequest;
import wowchat.common.Global$;
import wowchat.game.GameCommandHandler;
import wowchat.game.GuildInfo;
import wowchat.game.GuildMember;
import wowchat.game.GamePacketHandler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MenuHandler
 *
 * The bot's only two slash commands: /menu opens a private panel of buttons, select menus and
 * forms (with Back navigation and an Admin section), /help explains it. Every screen edits the
 * same ephemeral message, and any state a screen needs is carried in the component's customId.
 */
public final class MenuHandler {

    private MenuHandler() {}

    private static final Color EMBED_COLOR = Color.decode("#2b2d31");
    private static final String GITHUB_URL = "https://github.com/ChakraaThePanda/WoWChat-Discord-Bot";

    private static final String NO_PERMISSION =
        "❌ Admin Actions are only for the bot owner, the server owner and the roles listed in `commandRoleIds` in wowchat.conf.";
    private static final String NOT_CONNECTED =
        "❌ The bot isn't connected to the WoW server right now. Try again in a minute.";

    private static final int PAGE_SIZE = 25; // Discord's per-select option cap
    private static final int WHO_TIMEOUT_SECONDS = 10;

    private static volatile String ownerId;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MenuHandler");
        t.setDaemon(true);
        return t;
    });

    // -------------------------------------------------------------------------
    // Registration and routing
    // -------------------------------------------------------------------------

    public static void registerCommands(JDA jda) {
        // Replaces every previously registered command, so old ones disappear from Discord
        jda.updateCommands().addCommands(
            Commands.slash("menu", "Open the bot menu").setContexts(InteractionContextType.GUILD),
            Commands.slash("help", "Show information and documentation for this bot")
        ).queue(
            cmds -> System.out.println("[Menu] Registered /menu and /help."),
            err  -> System.err.println("[Menu] Failed to register commands: " + err.getMessage())
        );
        jda.retrieveApplicationInfo().queue(
            info -> ownerId = info.getOwner().getId(),
            err  -> System.err.println("[Menu] Failed to look up the bot owner: " + err.getMessage())
        );
    }

    public static void handleSlashCommand(SlashCommandInteractionEvent event) {
        try {
            if (event.getName().equals("menu")) {
                Screen s = mainScreen(event);
                event.replyEmbeds(s.embed).setComponents(s.rows).setEphemeral(true).queue();
            } else if (event.getName().equals("help")) {
                event.replyEmbeds(helpEmbed()).setEphemeral(true).queue();
            }
        } catch (Throwable t) {
            fail(event, t);
        }
    }

    public static void handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("menu:")) return;
        try {
            if (id.startsWith("menu:admin") && !isAdmin(event)) {
                event.reply(NO_PERMISSION).setEphemeral(true).queue();
                return;
            }

            if (id.equals("menu:back"))   { show(event, null, mainScreen(event)); return; }
            if (id.equals("menu:online")) { show(event, null, onlineScreen()); return; }
            if (id.equals("menu:post"))   { postToChannel(event); return; }

            // Look Up: a player, or crafters for a profession
            if (id.equals("menu:lookup")) { show(event, null, lookUpScreen()); return; }
            if (id.equals("menu:who"))    { event.replyModal(nameModal("modal:who", "Look Up Player")).queue(); return; }

            // My Characters: your links and their professions
            if (id.equals("menu:chars"))         { show(event, null, charactersScreen(event)); return; }
            if (id.equals("menu:chars:claim"))   { event.replyModal(nameModal("modal:chars:claim", "Claim a Character")).queue(); return; }
            if (id.equals("menu:chars:unclaim")) { show(event, null, unclaimPickScreen(event)); return; }
            if (id.startsWith("menu:chars:unclaimdo:")) {
                long guid = Long.parseLong(id.substring("menu:chars:unclaimdo:".length()));
                show(event, unclaim(event, guid), charactersScreen(event));
                return;
            }
            if (id.equals("menu:prof:add"))    { show(event, null, pickCharacterScreen(event)); return; }
            if (id.equals("menu:prof:remove")) { show(event, null, removeProfessionScreen(event, null, false)); return; }

            // Admin Actions and its sections
            if (id.equals("menu:admin"))        { show(event, null, adminScreen()); return; }
            if (id.equals("menu:admin:ranks"))  { show(event, null, ranksScreen()); return; }
            if (id.equals("menu:admin:blocks")) { show(event, null, blocksScreen()); return; }
            if (id.equals("menu:admin:chars"))  { show(event, null, adminCharactersScreen()); return; }
            if (id.equals("menu:admin:lists"))  { show(event, null, listsScreen()); return; }
            if (id.startsWith("menu:admin:ask:")) {
                String action = id.substring("menu:admin:ask:".length());
                event.replyModal(nameModal("modal:admin:" + action, ADMIN_TITLES.get(action))).queue();
                return;
            }
            if (id.startsWith("menu:admin:do:")) {
                // menu:admin:do:<action>:<name>
                String[] parts = id.substring("menu:admin:do:".length()).split(":", 2);
                show(event, runAdminAction(parts[0], parts[1]), sectionScreen(parts[0]));
                return;
            }
            if (id.startsWith("menu:admin:list:")) {
                // menu:admin:list:<unban|unignore>:<page>
                String[] parts = id.substring("menu:admin:list:".length()).split(":");
                Screen s = removeFromListScreen(parts[0], Integer.parseInt(parts[1]));
                show(event, s == null ? emptyListMessage(parts[0]) : null, s == null ? blocksScreen() : s);
                return;
            }
            if (id.equals("menu:admin:chars:give"))   { event.replyModal(nameModal("modal:admin:chars:give", "Give to Member")).queue(); return; }
            if (id.equals("menu:admin:chars:remove")) { event.replyModal(nameModal("modal:admin:chars:remove", "Remove Link")).queue(); return; }
            if (id.equals("menu:admin:chars:member")) { show(event, null, memberPickScreen()); return; }
            if (id.startsWith("menu:admin:chars:givedo:")) {
                // menu:admin:chars:givedo:<guid>:<discord user id>
                String[] parts = id.substring("menu:admin:chars:givedo:".length()).split(":");
                show(event, giveCharacter(event, Long.parseLong(parts[0]), parts[1]), adminCharactersScreen());
                return;
            }
            if (id.startsWith("menu:admin:chars:removedo:")) {
                long guid = Long.parseLong(id.substring("menu:admin:chars:removedo:".length()));
                show(event, removeLink(event, guid), adminCharactersScreen());
                return;
            }
            if (id.equals("menu:admin:prof:add"))    { event.replyModal(nameModal("modal:admin:prof:addchar", "Add / Update Profession")).queue(); return; }
            if (id.equals("menu:admin:prof:remove")) { event.replyModal(nameModal("modal:admin:prof:removechar", "Remove Profession")).queue(); return; }

            // A button from an older menu: start over from the main menu
            show(event, null, mainScreen(event));
        } catch (Throwable t) {
            fail(event, t);
        }
    }

    public static void handleSelect(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("menu:")) return;
        try {
            if (id.startsWith("menu:admin") && !isAdmin(event)) {
                event.reply(NO_PERMISSION).setEphemeral(true).queue();
                return;
            }
            String value = event.getValues().get(0);

            if (id.equals("menu:chars:unclaimsel")) {
                show(event, null, unclaimConfirmScreen(event, Long.parseLong(value)));
                return;
            }
            if (id.equals("menu:prof:findsel")) {
                show(event, null, findCraftersScreen(value));
                return;
            }

            // Profession screens are shared: "menu:admin:prof:..." is the admin's copy, for any character
            boolean admin = id.startsWith("menu:admin:prof:");
            String pid = admin ? "menu:prof:" + id.substring("menu:admin:prof:".length()) : id;

            if (pid.equals("menu:prof:addchar")) {
                show(event, null, pickProfessionScreen(value, false));
                return;
            }
            if (pid.startsWith("menu:prof:addprof:")) {
                String character = pid.substring("menu:prof:addprof:".length());
                event.replyModal(skillModal(character, value, admin)).queue();
                return;
            }
            if (pid.equals("menu:prof:removesel")) {
                // value is <character>:<profession>
                String[] parts = value.split(":", 2);
                String error = ProfessionManager.remove(parts[0], parts[1], event.getUser().getId(), event.getGuild());
                String result = error == null ? "✅ Removed **" + parts[1] + "** from **" + parts[0] + "**." : "❌ " + error;
                show(event, result, admin ? adminCharactersScreen() : charactersScreen(event));
                return;
            }
            if (id.startsWith("menu:admin:pick:")) {
                // menu:admin:pick:<unban|unignore>
                String action = id.substring("menu:admin:pick:".length());
                show(event, runAdminAction(action, value), blocksScreen());
            }
        } catch (Throwable t) {
            fail(event, t);
        }
    }

    // Discord member pickers: admins choosing who gets a character, or whose characters to see
    public static void handleEntitySelect(EntitySelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("menu:")) return;
        try {
            if (id.startsWith("menu:admin") && !isAdmin(event)) {
                event.reply(NO_PERMISSION).setEphemeral(true).queue();
                return;
            }
            if (event.getMentions().getUsers().isEmpty()) return;
            String userId = event.getMentions().getUsers().get(0).getId();

            if (id.startsWith("menu:admin:chars:giveto:")) {
                long guid = Long.parseLong(id.substring("menu:admin:chars:giveto:".length()));
                show(event, null, giveConfirmScreen(guid, userId));
                return;
            }
            if (id.equals("menu:admin:chars:membersel")) {
                show(event, null, memberCharactersScreen(userId));
            }
        } catch (Throwable t) {
            fail(event, t);
        }
    }

    public static void handleModal(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("modal:")) return;
        try {
            if (id.startsWith("modal:admin") && !isAdmin(event)) {
                event.reply(NO_PERMISSION).setEphemeral(true).queue();
                return;
            }

            if (id.equals("modal:chars:claim")) {
                startClaim(event, capitalize(value(event, "name")));
                return;
            }
            if (id.equals("modal:admin:chars:give")) {
                show(event, null, givePickScreen(capitalize(value(event, "name"))));
                return;
            }
            if (id.equals("modal:admin:chars:remove")) {
                show(event, null, removeLinkConfirmScreen(capitalize(value(event, "name"))));
                return;
            }

            if (id.equals("modal:who")) {
                lookUpPlayer(event, capitalize(value(event, "name")));
                return;
            }
            // Admin: professions of any guild character, by name
            if (id.equals("modal:admin:prof:addchar")) {
                String name = capitalize(value(event, "name"));
                if (GuildDataCache.findMember(name) == null) {
                    show(event, "❌ **" + name + "** isn't in the guild.", adminCharactersScreen());
                } else {
                    show(event, null, pickProfessionScreen(name, true));
                }
                return;
            }
            if (id.equals("modal:admin:prof:removechar")) {
                show(event, null, removeProfessionScreen(event, capitalize(value(event, "name")), true));
                return;
            }
            if (id.startsWith("modal:prof:skill:") || id.startsWith("modal:admin:prof:skill:")) {
                // modal:[admin:]prof:skill:<character>:<profession>
                boolean admin = id.startsWith("modal:admin:");
                String rest = id.substring(id.indexOf("prof:skill:") + "prof:skill:".length());
                String[] parts = rest.split(":", 2);
                show(event, addProfession(event, parts[0], parts[1], value(event, "skill")),
                    admin ? adminCharactersScreen() : charactersScreen(event));
                return;
            }
            if (id.startsWith("modal:admin:")) {
                String action = id.substring("modal:admin:".length());
                String name = capitalize(value(event, "name"));
                String problem = validateAdminAction(action, name);
                if (problem != null) {
                    show(event, problem, sectionScreen(action));
                } else {
                    show(event, null, confirmScreen(action, name));
                }
            }
        } catch (Throwable t) {
            fail(event, t);
        }
    }

    // -------------------------------------------------------------------------
    // Main menu
    // -------------------------------------------------------------------------

    private static Screen mainScreen(IReplyCallback event) {
        GamePacketHandler game = game();
        List<String> lines = new ArrayList<>();
        if (game == null) {
            lines.add("WoW Server: ❌ **Not Connected** (reconnecting)");
        } else {
            GuildInfo info = game.getGuildInfo();
            if (info != null) lines.add("Guild: **" + info.name() + "**");
            lines.add("Online: **" + GuildOnlineListPublisher.getOnlineNames().size() + "** guild members");
        }
        List<String> mine = new ArrayList<>();
        for (CharacterStore.Record r : CharacterStore.linkedTo(event.getUser().getId())) mine.add(r.name);
        if (mine.isEmpty()) {
            lines.add("Your Characters: ❌ **None Linked**");
            lines.add("");
            lines.add("Use **My Characters** to link one.");
        } else {
            lines.add("Your Characters: **" + String.join("**, **", mine) + "**");
        }

        // Quick lookups, then your own setup (green while nothing is linked), then admin tools
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
            Button.success("menu:online", "Who's Online"),
            Button.primary("menu:lookup", "Look Up")));
        rows.add(ActionRow.of(mine.isEmpty()
            ? Button.success("menu:chars", "My Characters")
            : Button.primary("menu:chars", "My Characters")));
        if (isAdmin(event)) rows.add(ActionRow.of(Button.secondary("menu:admin", "Admin Actions")));

        return new Screen(embed("WoWChat Menu", String.join("\n", lines)), rows);
    }

    // -------------------------------------------------------------------------
    // Who's Online, Look Up Player, Lists
    // -------------------------------------------------------------------------

    private static Screen onlineScreen() {
        GamePacketHandler game = game();
        if (game == null) return new Screen(embed("Who's Online?", NOT_CONNECTED), backOnly());

        List<String> online = GuildOnlineListPublisher.getOnlineNames();
        Collections.sort(online, String.CASE_INSENSITIVE_ORDER);

        StringBuilder sb = new StringBuilder();
        if (online.isEmpty()) {
            sb.append("No guild members are online right now.");
        } else {
            for (String entry : online) sb.append("- ").append(entry).append("\n");
        }
        MessageEmbed list = embed("Who's Online? (" + online.size() + ")", sb.toString());

        // The guild message of the day, kept low-key as a footer line
        Option<String> motd = game.getGuildMotd();
        if (motd.isDefined()) {
            list = new EmbedBuilder(list).setFooter(truncate("Guild MOTD: " + motd.get(), MessageEmbed.TEXT_MAX_LENGTH)).build();
        }
        return new Screen(list, postAndBackRow("menu:back"));
    }

    // Look Up: the crafters dropdown, then the player button under it
    private static Screen lookUpScreen() {
        StringSelectMenu.Builder select = StringSelectMenu.create("menu:prof:findsel").setPlaceholder("Find crafters: choose a profession");
        for (String p : ProfessionManager.validProfessions()) select.addOption(p, p);
        return new Screen(embed("Look Up", "Find guild members who can craft for you, or look up a player."),
            Arrays.asList(
                ActionRow.of(select.build()),
                ActionRow.of(Button.primary("menu:who", "Look Up Player")),
                backRow()));
    }

    private static void lookUpPlayer(ModalInteractionEvent event, String name) {
        GamePacketHandler game = game();
        if (game == null) {
            show(event, null, new Screen(embed("Look Up Player", NOT_CONNECTED), Collections.singletonList(backRow("menu:lookup"))));
            return;
        }

        event.deferEdit().queue();
        event.getHook().editOriginal(editData(null, new Screen(
            embed("Look Up Player", "🔎 Looking up **" + name + "**…"), Collections.singletonList(backRow("menu:lookup"))))).queue();

        // The game answers later through GamePacketHandler.handle_SMSG_WHO, which calls reply
        AtomicBoolean answered = new AtomicBoolean(false);
        List<ActionRow> resultRows = Arrays.asList(
            ActionRow.of(postButton(), Button.primary("menu:who", "Look Up Another")),
            backRow("menu:lookup"));
        Consumer<String> reply = result -> {
            if (!answered.compareAndSet(false, true)) return;
            event.getHook().editOriginal(editData(null, new Screen(embed("Look Up Player", result), resultRows))).queue();
        };
        scheduler.schedule(() -> {
            if (!answered.compareAndSet(false, true)) return;
            event.getHook().editOriginal(editData(null, new Screen(
                embed("Look Up Player", "❌ The WoW server didn't answer about **" + name + "**. Try again."), resultRows))).queue();
        }, WHO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        game.sendWho(new WhoRequest(name, reply));
    }

    private static Screen listsScreen() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ignored players' messages aren't relayed to Discord. ")
          .append("Banned players are also kicked from the guild automatically.\n\n");
        appendNameList(sb, "Ignored", Blocklist.ignoredNames());
        sb.append("\n");
        appendNameList(sb, "Banned", Blocklist.bannedNames());
        return new Screen(embed("Ignore & Ban Lists", sb.toString()), postAndBackRow("menu:admin:blocks"));
    }

    private static void appendNameList(StringBuilder sb, String title, List<String> names) {
        sb.append("**").append(title).append(" (").append(names.size()).append(")**\n");
        if (names.isEmpty()) sb.append("Nobody\n");
        for (String n : names) sb.append("- ").append(capitalize(n)).append("\n");
    }

    // -------------------------------------------------------------------------
    // My Characters: claim (link) and unclaim, and their professions
    // -------------------------------------------------------------------------

    private static final String THIS_SERVER_ONLY =
        "Links only count in this Discord server. Other servers running this bot keep their own.";

    private static Screen charactersScreen(IReplyCallback event) {
        return charactersScreen(event.getUser().getId());
    }

    private static Screen charactersScreen(String userId) {
        List<CharacterStore.Record> mine = CharacterStore.linkedTo(userId);
        boolean inGuild = false, anyProfession = false;
        StringBuilder sb = new StringBuilder();
        if (mine.isEmpty()) {
            sb.append("You have no linked characters yet. Press **Claim** to link one.\n");
        } else {
            for (CharacterStore.Record r : mine) {
                sb.append("- ").append(describe(r)).append("\n");
                if (findMemberByGuid(r.guid) == null) continue;
                inGuild = true;
                List<String> profs = CharacterStore.professions(r.guid);
                anyProfession |= !profs.isEmpty();
                sb.append("  ").append(profs.isEmpty() ? "No professions yet" : formatAll(profs)).append("\n");
            }
        }
        sb.append("\nCharacters in the guild can have up to ").append(ProfessionManager.MAX_PROFESSIONS)
          .append(" professions. They show in the guild roster and in **Look Up**.\n")
          .append(THIS_SERVER_ONLY);

        List<ActionRow> rows = Arrays.asList(
            ActionRow.of(
                Button.success("menu:chars:claim", "Claim").withDisabled(game() == null),
                Button.danger("menu:chars:unclaim", "Unclaim").withDisabled(mine.isEmpty())),
            ActionRow.of(
                Button.primary("menu:prof:add", "Add / Update Profession").withDisabled(!inGuild),
                Button.danger("menu:prof:remove", "Remove Profession").withDisabled(!anyProfession)),
            backRow());
        String title = "My Characters" + (mine.isEmpty() ? "" : " (" + mine.size() + ")");
        String footer = game() == null ? "\n\n" + NOT_CONNECTED : "";
        return new Screen(embed(title, sb + footer), rows);
    }

    // "**Arthas**: Level 80 Death Knight, Officer" for guild members, "**Bob**: not in the guild" otherwise
    private static String describe(CharacterStore.Record r) {
        GuildMember m = findMemberByGuid(r.guid);
        if (m == null) return "**" + r.name + "**: not in the guild";
        String rank = rankName(game() == null ? null : game().getGuildInfo(), m.rankIndex());
        return "**" + m.name() + "**: Level " + (m.level() & 0xFF) + " " + GameData.className(m.charClass())
            + (rank != null ? ", " + rank : "");
    }

    private static void startClaim(ModalInteractionEvent event, String character) {
        String userId = event.getUser().getId();
        if (game() == null) {
            show(event, NOT_CONNECTED, charactersScreen(userId));
            return;
        }
        // Later updates (code received, linked, expired) replace this message
        Consumer<String> status = msg ->
            event.getHook().editOriginal(editData(msg, charactersScreen(userId))).queue(ok -> {}, err -> {});
        String serverName = event.getGuild() != null ? event.getGuild().getName() : "this Discord server";
        CharacterClaims.Start start = CharacterClaims.start(userId, event.getUser().getName(), serverName, character, status);
        if (start.problem != null) {
            show(event, start.problem, charactersScreen(userId));
            return;
        }
        String bot = Blocklist.botCharacter();
        String steps = String.join("\n",
            "1. Log in to **" + character + "** in WoW.",
            "2. Whisper this to the bot: `/w " + bot + " " + start.code + "`",
            "3. The bot whispers back with your Discord name. Whisper `yes` to confirm.",
            "",
            "The code works for 10 minutes. This message updates when it's done.",
            "Wrong name, or the code expired? Press **New Code**.",
            "",
            THIS_SERVER_ONLY);
        show(event, null, new Screen(embed("Claim " + character, steps), Arrays.asList(
            ActionRow.of(Button.primary("menu:chars:claim", "New Code")),
            backRow("menu:chars"))));
    }

    private static Screen unclaimPickScreen(IReplyCallback event) {
        List<CharacterStore.Record> mine = CharacterStore.linkedTo(event.getUser().getId());
        if (mine.isEmpty()) return charactersScreen(event);
        StringSelectMenu.Builder select = StringSelectMenu.create("menu:chars:unclaimsel").setPlaceholder("Choose a character");
        for (CharacterStore.Record r : limit(mine, PAGE_SIZE)) select.addOption(r.name, String.valueOf(r.guid));
        return new Screen(embed("Unclaim", "Which character do you want to unlink from your Discord account?"),
            Arrays.asList(ActionRow.of(select.build()), backRow("menu:chars")));
    }

    private static Screen unclaimConfirmScreen(IReplyCallback event, long guid) {
        CharacterStore.Record r = CharacterStore.get(guid);
        if (r == null || !event.getUser().getId().equals(r.discordId)) return charactersScreen(event);
        return new Screen(embed("Unclaim", "Unlink **" + r.name + "** from your Discord account? You can claim it again later."),
            Collections.singletonList(ActionRow.of(
                Button.danger("menu:chars:unclaimdo:" + guid, "Yes, unclaim"),
                Button.secondary("menu:chars", "Cancel"))));
    }

    private static String unclaim(IReplyCallback event, long guid) {
        CharacterStore.Record r = CharacterStore.get(guid);
        String userId = event.getUser().getId();
        if (r == null || !userId.equals(r.discordId)) return "ℹ️ That character isn't linked to you.";
        CharacterStore.unlink(guid, "self:" + userId);
        return "✅ **" + r.name + "** is no longer linked to your Discord account.";
    }

    // Admin: Give to Member. Only guild members, so every link has the character's GUID.
    private static Screen givePickScreen(String name) {
        if (game() == null) return charactersAdminResult(NOT_CONNECTED);
        GuildMember m = GuildDataCache.findMember(name);
        if (m == null) {
            return charactersAdminResult("❌ **" + name + "** isn't in the guild. Only guild members can be given. "
                + "The player can claim it from /menu → **My Characters**.");
        }
        String owner = CharacterStore.discordIdOf(m);
        String text = "Who should **" + m.name() + "** be linked to?"
            + (owner != null ? "\nIt's linked to <@" + owner + "> now." : "");
        EntitySelectMenu select = EntitySelectMenu.create("menu:admin:chars:giveto:" + m.guid(), EntitySelectMenu.SelectTarget.USER)
            .setPlaceholder("Choose a Discord member").build();
        return new Screen(embed("Give to Member", text), Arrays.asList(ActionRow.of(select), backRow("menu:admin:chars")));
    }

    private static Screen giveConfirmScreen(long guid, String userId) {
        GuildMember m = findMemberByGuid(guid);
        if (m == null) return charactersAdminResult("❌ That character isn't in the guild anymore.");
        return new Screen(embed("Give to Member", "Link **" + m.name() + "** to <@" + userId + ">?"),
            Collections.singletonList(ActionRow.of(
                Button.success("menu:admin:chars:givedo:" + guid + ":" + userId, "Yes, give"),
                Button.secondary("menu:admin:chars", "Cancel"))));
    }

    private static String giveCharacter(IReplyCallback event, long guid, String userId) {
        GuildMember m = findMemberByGuid(guid);
        if (m == null) return "❌ That character isn't in the guild anymore.";
        CharacterStore.setLink(guid, m.name(), userId, "admin:" + event.getUser().getId());
        return "✅ **" + m.name() + "** is now linked to <@" + userId + ">.";
    }

    // Admin: Remove Link. Any link, in the guild or not.
    private static Screen removeLinkConfirmScreen(String name) {
        GuildMember m = GuildDataCache.findMember(name);
        CharacterStore.Record r = m != null ? CharacterStore.get(m.guid()) : CharacterStore.byName(name);
        if (r == null || !r.isLinked()) return charactersAdminResult("ℹ️ **" + name + "** isn't linked to anyone.");
        return new Screen(embed("Remove Link", "Remove the link between **" + r.name + "** and <@" + r.discordId + ">?"),
            Collections.singletonList(ActionRow.of(
                Button.danger("menu:admin:chars:removedo:" + r.guid, "Yes, remove"),
                Button.secondary("menu:admin:chars", "Cancel"))));
    }

    private static String removeLink(IReplyCallback event, long guid) {
        CharacterStore.Record r = CharacterStore.get(guid);
        if (r == null || !CharacterStore.unlink(guid, "admin:" + event.getUser().getId())) {
            return "ℹ️ That character isn't linked to anyone.";
        }
        return "✅ **" + r.name + "** is no longer linked to <@" + r.discordId + ">.";
    }

    // Admin: Member's Characters
    private static Screen memberPickScreen() {
        EntitySelectMenu select = EntitySelectMenu.create("menu:admin:chars:membersel", EntitySelectMenu.SelectTarget.USER)
            .setPlaceholder("Choose a Discord member").build();
        return new Screen(embed("Member's Characters", "Whose characters do you want to see?"),
            Arrays.asList(ActionRow.of(select), backRow("menu:admin:chars")));
    }

    private static Screen memberCharactersScreen(String userId) {
        List<CharacterStore.Record> chars = CharacterStore.linkedTo(userId);
        StringBuilder sb = new StringBuilder("<@").append(userId).append(">\n\n");
        if (chars.isEmpty()) sb.append("No linked characters.");
        for (CharacterStore.Record r : chars) sb.append("- ").append(describe(r)).append("\n");
        return new Screen(embed("Member's Characters", sb.toString()),
            Arrays.asList(ActionRow.of(Button.secondary("menu:admin:chars:member", "Another Member")), backRow("menu:admin:chars")));
    }

    private static Screen charactersAdminResult(String message) {
        return new Screen(embed("Characters", message), Collections.singletonList(backRow("menu:admin:chars")));
    }

    // -------------------------------------------------------------------------
    // Professions. Members change their own characters' from My Characters; admins change
    // anyone's from Admin Actions -> Characters. admin picks which ids and Back buttons are used.
    // -------------------------------------------------------------------------

    private static String profPrefix(boolean admin) {
        return admin ? "menu:admin:prof:" : "menu:prof:";
    }

    private static String profBack(boolean admin) {
        return admin ? "menu:admin:chars" : "menu:chars";
    }

    // The member's own guild characters
    private static Screen pickCharacterScreen(IReplyCallback event) {
        StringSelectMenu.Builder select = StringSelectMenu.create("menu:prof:addchar").setPlaceholder("Choose a character");
        for (String c : limit(linkedCharacters(event.getUser().getId()), PAGE_SIZE)) select.addOption(c, c);
        if (select.getOptions().isEmpty()) return charactersScreen(event);
        return new Screen(embed("Add / Update Profession", "Which character is the profession for?"),
            Arrays.asList(ActionRow.of(select.build()), backRow("menu:chars")));
    }

    private static Screen pickProfessionScreen(String character, boolean admin) {
        List<String> current = new ArrayList<>();
        for (String p : ProfessionManager.getProfessions(character)) current.add(ProfessionManager.profName(p));

        StringSelectMenu.Builder select = StringSelectMenu.create(profPrefix(admin) + "addprof:" + character).setPlaceholder("Choose a profession");
        for (String p : ProfessionManager.validProfessions()) {
            if (current.contains(p)) select.addOption(p, p, "Already has it: update the skill level");
            else select.addOption(p, p);
        }
        return new Screen(embed("Add / Update Profession", "Which profession for **" + character + "**?"),
            Arrays.asList(ActionRow.of(select.build()), backRow(profBack(admin))));
    }

    private static String addProfession(IReplyCallback event, String character, String profession, String skillText) {
        Integer skill = null;
        if (!skillText.isEmpty()) {
            try {
                skill = Integer.parseInt(skillText);
            } catch (NumberFormatException e) {
                return "❌ The skill level must be a number between 1 and " + GameData.skillCap() + ".";
            }
        }
        String error = ProfessionManager.add(character, profession, skill, event.getUser().getId(), event.getGuild());
        if (error != null) return "❌ " + error;
        return "✅ Added **" + profession + (skill != null ? " (" + skill + ")" : "") + "** for **" + character + "**.";
    }

    // character null: the caller's own characters, otherwise that one character (admin typed a name)
    private static Screen removeProfessionScreen(IReplyCallback event, String character, boolean admin) {
        List<String> characters = character == null ? linkedCharacters(event.getUser().getId()) : Collections.singletonList(character);

        StringSelectMenu.Builder select = StringSelectMenu.create(profPrefix(admin) + "removesel").setPlaceholder("Choose a profession to remove");
        int count = 0;
        for (String c : characters) {
            for (String stored : ProfessionManager.getProfessions(c)) {
                if (count >= PAGE_SIZE) break;
                select.addOption(c + ": " + ProfessionManager.formatProfession(stored), c + ":" + ProfessionManager.profName(stored));
                count++;
            }
        }

        if (select.getOptions().isEmpty()) {
            String who = character == null ? "Your characters have" : "**" + character + "** has";
            return new Screen(embed("Remove Profession", who + " no professions to remove."), Collections.singletonList(backRow(profBack(admin))));
        }
        return new Screen(embed("Remove Profession", "Which profession do you want to remove?"),
            Arrays.asList(ActionRow.of(select.build()), backRow(profBack(admin))));
    }

    private static Screen findCraftersScreen(String profession) {
        List<String[]> results = ProfessionManager.listByProfession(profession);
        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) sb.append("Nobody in the guild has **").append(profession).append("** yet.");
        for (String[] entry : results) {
            String name = capitalize(entry[0]);
            String discordId = discordIdOf(name);
            sb.append("- **").append(name).append("**")
              .append(discordId != null ? " (<@" + discordId + ">)" : "")
              .append(": ").append(ProfessionManager.formatProfession(entry[1])).append("\n");
        }
        return new Screen(embed(profession + " (" + results.size() + ")", sb.toString()), postAndBackRow("menu:lookup"));
    }

    // -------------------------------------------------------------------------
    // Admin Actions
    // -------------------------------------------------------------------------

    private static final java.util.Map<String, String> ADMIN_TITLES = new java.util.HashMap<>();
    static {
        ADMIN_TITLES.put("promote", "Promote");
        ADMIN_TITLES.put("demote", "Demote");
        ADMIN_TITLES.put("ignore", "Ignore");
        ADMIN_TITLES.put("ban", "Ban");
    }

    private static Screen adminScreen() {
        String description = String.join("\n",
            "**Guild Ranks**: promote or demote a guild member.",
            "**Ignore & Ban**: keep players' messages out of Discord, or out of the guild.",
            "**Characters**: link members' characters, and edit anyone's professions.");
        List<ActionRow> rows = Arrays.asList(
            ActionRow.of(
                Button.primary("menu:admin:ranks", "Guild Ranks"),
                Button.primary("menu:admin:blocks", "Ignore & Ban"),
                Button.primary("menu:admin:chars", "Characters")),
            backRow());
        return new Screen(embed("Admin Actions", description), rows);
    }

    private static Screen ranksScreen() {
        String description = "**Promote / Demote**: move a guild member one rank up or down in game. "
            + "The bot can only change members below its own rank.";
        return new Screen(embed("Guild Ranks", description), Arrays.asList(
            ActionRow.of(
                Button.success("menu:admin:ask:promote", "Promote"),
                Button.danger("menu:admin:ask:demote", "Demote")),
            backRow("menu:admin")));
    }

    private static Screen blocksScreen() {
        String description = String.join("\n",
            "**Ignore**: stop relaying a player's messages to Discord.",
            "**Ban**: ignore a player, and kick them from the guild automatically.",
            "**Unignore / Unban**: pick someone from the list to take them off.");
        return new Screen(embed("Ignore & Ban", description), Arrays.asList(
            ActionRow.of(
                Button.primary("menu:admin:ask:ignore", "Ignore"),
                Button.danger("menu:admin:ask:ban", "Ban")),
            ActionRow.of(
                Button.secondary("menu:admin:list:unignore:0", "Unignore"),
                Button.secondary("menu:admin:list:unban:0", "Unban")),
            ActionRow.of(Button.primary("menu:admin:lists", "Ignore & Ban Lists")),
            backRow("menu:admin")));
    }

    private static Screen adminCharactersScreen() {
        String description = String.join("\n",
            "**Give to Member**: link a guild character to a Discord member, or move it to someone else.",
            "**Remove Link**: unlink any character from its Discord account.",
            "**Member's Characters**: see everything a member has linked.",
            "**Add / Remove Profession**: change any guild character's professions.");
        return new Screen(embed("Characters", description), Arrays.asList(
            ActionRow.of(
                Button.primary("menu:admin:chars:give", "Give to Member"),
                Button.danger("menu:admin:chars:remove", "Remove Link"),
                Button.secondary("menu:admin:chars:member", "Member's Characters")),
            ActionRow.of(
                Button.primary("menu:admin:prof:add", "Add / Update Profession"),
                Button.danger("menu:admin:prof:remove", "Remove Profession")),
            backRow("menu:admin")));
    }

    // The admin section an action belongs to, to come back to after it
    private static Screen sectionScreen(String action) {
        return action.equals("promote") || action.equals("demote") ? ranksScreen() : blocksScreen();
    }

    private static String sectionId(String action) {
        return action.equals("promote") || action.equals("demote") ? "menu:admin:ranks" : "menu:admin:blocks";
    }

    // Returns an error message, or null when the action can go ahead
    private static String validateAdminAction(String action, String name) {
        if (action.equals("ignore") && Blocklist.isIgnored(name)) return "ℹ️ **" + name + "** is already on the ignore list.";
        if (action.equals("ban") && Blocklist.isBanned(name)) return "ℹ️ **" + name + "** is already on the ban list.";
        if (!action.equals("promote") && !action.equals("demote")) return null;

        GamePacketHandler game = game();
        if (game == null) return NOT_CONNECTED;
        GuildMember target = GuildDataCache.findMember(name);
        if (target == null) return "❌ **" + name + "** isn't in the guild.";
        GuildMember bot = GuildDataCache.findMember(Blocklist.botCharacter());
        GuildInfo info = game.getGuildInfo();

        if (action.equals("promote") && bot != null) {
            if (target.rankIndex() - 1 == bot.rankIndex()) return "❌ Can't promote **" + name + "**: they would have the same rank as the bot.";
            if (target.rankIndex() <= bot.rankIndex()) return "❌ Can't promote **" + name + "**: their rank is already equal to or higher than the bot's.";
        }
        if (action.equals("demote") && info != null && target.rankIndex() >= lowestRankIndex(info)) {
            return "❌ Can't demote **" + name + "**: they're already at the lowest rank.";
        }
        return null;
    }

    private static Screen confirmScreen(String action, String name) {
        String question;
        switch (action) {
            case "promote":
            case "demote": {
                GamePacketHandler game = game();
                GuildMember target = game == null ? null : GuildDataCache.findMember(name);
                GuildInfo info = game == null ? null : game.getGuildInfo();
                String from = target == null ? null : rankName(info, target.rankIndex());
                String to = target == null ? null : rankName(info, target.rankIndex() + (action.equals("promote") ? -1 : 1));
                question = ADMIN_TITLES.get(action) + " **" + name + "**"
                    + (from != null && to != null ? " from **" + from + "** to **" + to + "**" : "") + "?";
                break;
            }
            case "ignore":
                question = "Ignore **" + name + "**? Their messages will no longer be relayed to Discord.";
                break;
            default:
                question = "Ban **" + name + "**? Their messages will no longer be relayed to Discord, and they'll be kicked from the guild automatically.";
        }
        boolean harsh = action.equals("demote") || action.equals("ban");
        Button yes = harsh
            ? Button.danger("menu:admin:do:" + action + ":" + name, "Yes, " + action)
            : Button.success("menu:admin:do:" + action + ":" + name, "Yes, " + action);
        return new Screen(embed(ADMIN_TITLES.get(action), question),
            Collections.singletonList(ActionRow.of(yes, Button.secondary(sectionId(action), "Cancel"))));
    }

    private static String runAdminAction(String action, String name) {
        switch (action) {
            case "promote":
            case "demote": {
                String problem = validateAdminAction(action, name);
                if (problem != null) return problem;
                GamePacketHandler game = game();
                if (action.equals("promote")) game.sendGuildPromote(name);
                else game.sendGuildDemote(name);
                game.forceRosterRefresh();
                return "✅ " + ADMIN_TITLES.get(action) + " sent for **" + name + "**.";
            }
            case "ignore":
                return Blocklist.ignore(name)
                    ? "✅ **" + name + "** is now ignored. Their messages won't be relayed to Discord."
                    : "ℹ️ **" + name + "** is already on the ignore list.";
            case "unignore":
                return Blocklist.unignore(name)
                    ? "✅ **" + capitalize(name) + "** is no longer ignored."
                    : "ℹ️ **" + capitalize(name) + "** isn't on the ignore list.";
            case "ban":
            {
                // Kicked right away if they're in the guild, not at the next check
                boolean inGuild = GuildDataCache.findMember(name) != null;
                if (!Blocklist.banAndKick(name, game())) return "ℹ️ **" + name + "** is already on the ban list.";
                return "✅ **" + name + "** is now banned." + (inGuild ? " They're being kicked from the guild." : "");
            }
            case "unban":
                return Blocklist.unban(name)
                    ? "✅ **" + capitalize(name) + "** is no longer banned."
                    : "ℹ️ **" + capitalize(name) + "** isn't on the ban list.";
            default:
                return "❌ Unknown action.";
        }
    }

    // Select menu of who's on the ignore or ban list, 25 per page. Null when the list is empty.
    private static Screen removeFromListScreen(String action, int page) {
        List<String> names = action.equals("unban")
            ? Blocklist.bannedNames()
            : Blocklist.ignoredNames();
        if (names.isEmpty()) return null;

        int pages = (names.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.max(0, Math.min(page, pages - 1));
        StringSelectMenu.Builder select = StringSelectMenu.create("menu:admin:pick:" + action)
            .setPlaceholder(action.equals("unban") ? "Choose who to unban" : "Choose who to unignore");
        for (String n : names.subList(page * PAGE_SIZE, Math.min(names.size(), (page + 1) * PAGE_SIZE))) {
            select.addOption(capitalize(n), n);
        }

        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(select.build()));
        if (pages > 1) {
            rows.add(ActionRow.of(
                Button.secondary("menu:admin:list:" + action + ":" + (page - 1), "◀ Prev").withDisabled(page == 0),
                Button.secondary("menu:admin:list:" + action + ":" + (page + 1), "Next ▶").withDisabled(page >= pages - 1)));
        }
        rows.add(backRow("menu:admin:blocks"));
        String title = action.equals("unban") ? "Unban" : "Unignore";
        String footer = pages > 1 ? "\nPage " + (page + 1) + " of " + pages + "." : "";
        return new Screen(embed(title, "Pick a player to remove from the list." + footer), rows);
    }

    private static String emptyListMessage(String action) {
        return action.equals("unban") ? "ℹ️ Nobody is on the ban list." : "ℹ️ Nobody is on the ignore list.";
    }

    // -------------------------------------------------------------------------
    // /help
    // -------------------------------------------------------------------------

    private static MessageEmbed helpEmbed() {
        return new EmbedBuilder()
            .setColor(EMBED_COLOR)
            .setTitle("WoWChat Discord Bot", GITHUB_URL)
            .setDescription("Connects your WoW guild to Discord: chat goes both ways, and guild panels update themselves.")
            .addField("`/menu`", String.join("\n",
                "Opens the bot menu. Only you can see it.",
                "- **Who's Online**: who's playing right now, with level, race, class and zone.",
                "- **Look Up**: level, class, zone and guild rank of any player, or who can craft a profession.",
                "- **My Characters**: link your WoW characters to your Discord account, and set their professions.",
                "- **Admin Actions** *(bot admins)*: guild ranks, ignore and ban lists, and members' characters.",
                "Most views have **Post to channel** to share them."), false)
            .addField("Link your characters", String.join("\n",
                "1. In `/menu`, press **My Characters**, then **Claim**, and type your character's name.",
                "2. Log in to that character and whisper the code to the bot.",
                "3. The bot whispers back with your Discord name. Whisper `yes` to confirm.",
                "Linked characters show your Discord name in the guild panels and next to your messages, "
                    + "and let you set their professions. Links only count in this Discord server."), false)
            .addField("`/help`", "Show this message.", false)
            .addField("GitHub", "[github.com/ChakraaThePanda/WoWChat-Discord-Bot](" + GITHUB_URL + ")", false)
            .build();
    }

    // -------------------------------------------------------------------------
    // Shared pieces
    // -------------------------------------------------------------------------

    private static final class Screen {
        final MessageEmbed embed;
        final List<ActionRow> rows;

        Screen(MessageEmbed embed, List<ActionRow> rows) {
            this.embed = embed;
            this.rows = rows;
        }
    }

    private static MessageEmbed embed(String title, String description) {
        if (description.length() > MessageEmbed.DESCRIPTION_MAX_LENGTH) {
            description = description.substring(0, MessageEmbed.DESCRIPTION_MAX_LENGTH - 20);
            description = description.substring(0, description.lastIndexOf('\n') + 1) + "…and more";
        }
        return new EmbedBuilder().setColor(EMBED_COLOR).setTitle(title).setDescription(description).build();
    }

    private static MessageEditData editData(String content, Screen screen) {
        return new MessageEditBuilder()
            .setContent(content == null ? "" : content)
            .setEmbeds(screen.embed)
            .setComponents(screen.rows)
            .build();
    }

    // Replaces the menu message with a new screen. `content` shows above it, for action results.
    private static void show(IMessageEditCallback event, String content, Screen screen) {
        event.editMessage(editData(content, screen)).queue();
    }

    private static Button backButton(String target) {
        return Button.secondary(target, "◀ Back");
    }

    private static ActionRow backRow() {
        return backRow("menu:back");
    }

    private static ActionRow backRow(String target) {
        return ActionRow.of(backButton(target));
    }

    private static List<ActionRow> backOnly() {
        return Collections.singletonList(backRow());
    }

    private static Button postButton() {
        return Button.primary("menu:post", "Post to channel");
    }

    // Back always sits on its own row, under the screen's other buttons
    private static List<ActionRow> postAndBackRow(String backTarget) {
        return Arrays.asList(ActionRow.of(postButton()), backRow(backTarget));
    }

    private static List<ActionRow> rows(List<Button> buttons) {
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(buttons.size(), i + 5))));
        }
        return rows;
    }

    // Posts the embed currently shown in the menu to the channel, without pinging anyone
    private static void postToChannel(ButtonInteractionEvent event) {
        event.deferEdit().queue();
        event.getChannel().sendMessageEmbeds(event.getMessage().getEmbeds())
            .setAllowedMentions(Collections.emptyList())
            .queue(
                ok -> event.getHook().sendMessage("✅ Posted to the channel.").setEphemeral(true).queue(),
                err -> event.getHook().sendMessage("❌ I can't post in this channel. Check that my role has **View Channel**, **Send Messages** and **Embed Links** here.").setEphemeral(true).queue());
    }

    private static Modal nameModal(String id, String title) {
        TextInput input = TextInput.create("name", "Character name", TextInputStyle.SHORT)
            .setRequired(true).setMinLength(2).setMaxLength(12).build();
        return Modal.create(id, title).addComponents(ActionRow.of(input)).build();
    }

    private static Modal skillModal(String character, String profession, boolean admin) {
        TextInput input = TextInput.create("skill", "Skill level (1-" + GameData.skillCap() + ", optional)", TextInputStyle.SHORT)
            .setRequired(false).setMaxLength(3).build();
        String prefix = admin ? "modal:admin:prof:skill:" : "modal:prof:skill:";
        return Modal.create(prefix + character + ":" + profession, profession + " for " + character)
            .addComponents(ActionRow.of(input)).build();
    }

    private static String value(ModalInteractionEvent event, String field) {
        ModalMapping m = event.getValue(field);
        return m == null ? "" : m.getAsString().trim();
    }

    // Any interaction failure ends on a message with a way back, never on "This interaction failed"
    private static void fail(IReplyCallback event, Throwable t) {
        System.err.println("[Menu] " + t);
        t.printStackTrace();
        String msg = "❌ Something went wrong: " + t.getMessage();
        try {
            if (event.isAcknowledged()) {
                event.getHook().editOriginal(editData(msg, new Screen(embed("WoWChat Menu", "Use **Back** to return to the menu."), Collections.singletonList(backRow())))).queue();
            } else {
                event.reply(msg).setEphemeral(true).queue();
            }
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Permissions and game data
    // -------------------------------------------------------------------------

    private static boolean isAdmin(IReplyCallback event) {
        String userId = event.getUser().getId();
        if (userId.equals(ownerId)) return true;
        Guild guild = event.getGuild();
        if (guild != null && userId.equals(guild.getOwnerId())) return true;
        Member member = event.getMember();
        return ProfessionManager.hasCommandRole(member);
    }

    /** The same check from just an ID (professions). Members are cached, so no request is sent. */
    static boolean isAdmin(String userId, Guild guild) {
        if (userId == null) return false;
        if (userId.equals(ownerId)) return true;
        if (guild == null) return false;
        if (userId.equals(guild.getOwnerId())) return true;
        return ProfessionManager.hasCommandRole(guild.getMemberById(userId));
    }

    private static GamePacketHandler game() {
        Option<GameCommandHandler> gameOpt = Global$.MODULE$.game();
        if (gameOpt == null || gameOpt.isEmpty() || !(gameOpt.get() instanceof GamePacketHandler)) return null;
        return (GamePacketHandler) gameOpt.get();
    }

    private static List<GuildMember> roster() {
        List<GuildMember> members = new ArrayList<>();
        GamePacketHandler game = game();
        if (game == null) return members;
        scala.collection.Iterator<GuildMember> it = game.getGuildRoster().valuesIterator();
        while (it.hasNext()) members.add(it.next());
        return members;
    }

    // This Discord user's characters that are in the guild, sorted by name (for professions)
    private static List<String> linkedCharacters(String userId) {
        List<String> names = new ArrayList<>();
        for (GuildMember m : roster()) {
            if (userId.equals(CharacterStore.discordIdOf(m))) names.add(m.name());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String discordIdOf(String character) {
        GuildMember m = GuildDataCache.findMember(character);
        return m != null ? CharacterStore.discordIdOf(m) : CharacterStore.discordIdOfName(character);
    }

    private static GuildMember findMemberByGuid(long guid) {
        for (GuildMember m : roster()) {
            if (m.guid() == guid) return m;
        }
        return null;
    }

    private static String rankName(GuildInfo info, int rankIndex) {
        if (info == null) return null;
        Option<String> rank = info.ranks().get(rankIndex);
        return rank.isDefined() ? rank.get() : null;
    }

    private static int lowestRankIndex(GuildInfo info) {
        int max = 0;
        scala.collection.Iterator<Object> it = info.ranks().keysIterator();
        while (it.hasNext()) max = Math.max(max, (Integer) it.next());
        return max;
    }

    private static String formatAll(List<String> stored) {
        List<String> parts = new ArrayList<>();
        for (String s : stored) parts.add(ProfessionManager.formatProfession(s));
        return String.join(", ", parts);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }
}
