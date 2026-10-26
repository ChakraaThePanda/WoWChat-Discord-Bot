package wowchat.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import scala.Option;
import wowchat.commands.CommandHandler$;
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
        "❌ Admin Actions are only for the bot owner and the roles listed in `commandRoleIds` in wowchat.conf.";
    private static final String NOT_CONNECTED =
        "❌ The bot isn't connected to the WoW server right now. Try again in a minute.";

    // Pseudo-option in character pickers that lets an admin type any guild member's name
    private static final String OTHER = "__other";
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
            if (id.equals("menu:who"))    { event.replyModal(nameModal("modal:who", "Look Up Player")).queue(); return; }

            if (id.equals("menu:prof"))        { show(event, null, professionsScreen(event)); return; }
            if (id.equals("menu:prof:add"))    { show(event, null, pickCharacterScreen(event, "menu:prof:addchar", "Add / Update Profession", "Which character is the profession for?")); return; }
            if (id.equals("menu:prof:remove")) { show(event, null, removeProfessionScreen(event, null)); return; }
            if (id.equals("menu:prof:find"))   { show(event, null, findCraftersPickScreen()); return; }

            if (id.equals("menu:admin")) { show(event, null, adminScreen()); return; }
            if (id.equals("menu:admin:lists")) { show(event, null, listsScreen()); return; }
            if (id.startsWith("menu:admin:ask:")) {
                String action = id.substring("menu:admin:ask:".length());
                event.replyModal(nameModal("modal:admin:" + action, ADMIN_TITLES.get(action))).queue();
                return;
            }
            if (id.startsWith("menu:admin:do:")) {
                // menu:admin:do:<action>:<name>
                String[] parts = id.substring("menu:admin:do:".length()).split(":", 2);
                show(event, runAdminAction(parts[0], parts[1]), adminScreen());
                return;
            }
            if (id.startsWith("menu:admin:list:")) {
                // menu:admin:list:<unban|unignore>:<page>
                String[] parts = id.substring("menu:admin:list:".length()).split(":");
                Screen s = removeFromListScreen(parts[0], Integer.parseInt(parts[1]));
                show(event, s == null ? emptyListMessage(parts[0]) : null, s == null ? adminScreen() : s);
            }
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

            if (id.equals("menu:prof:addchar")) {
                if (value.equals(OTHER)) {
                    if (!isAdmin(event)) { event.reply(NO_PERMISSION).setEphemeral(true).queue(); return; }
                    event.replyModal(nameModal("modal:prof:addchar", "Add / Update Profession")).queue();
                } else {
                    show(event, null, pickProfessionScreen(value));
                }
                return;
            }
            if (id.startsWith("menu:prof:addprof:")) {
                String character = id.substring("menu:prof:addprof:".length());
                event.replyModal(skillModal(character, value)).queue();
                return;
            }
            if (id.equals("menu:prof:removesel")) {
                if (value.equals(OTHER)) {
                    if (!isAdmin(event)) { event.reply(NO_PERMISSION).setEphemeral(true).queue(); return; }
                    event.replyModal(nameModal("modal:prof:removechar", "Remove Profession")).queue();
                    return;
                }
                // value is <character>:<profession>
                String[] parts = value.split(":", 2);
                String error = ProfessionManager.remove(parts[0], parts[1], event.getUser().getId(), event.getGuild());
                String result = error == null ? "✅ Removed **" + parts[1] + "** from **" + parts[0] + "**." : "❌ " + error;
                show(event, result, professionsScreen(event));
                return;
            }
            if (id.equals("menu:prof:findsel")) {
                show(event, null, findCraftersScreen(value));
                return;
            }
            if (id.startsWith("menu:admin:pick:")) {
                // menu:admin:pick:<unban|unignore>
                String action = id.substring("menu:admin:pick:".length());
                show(event, runAdminAction(action, value), adminScreen());
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

            if (id.equals("modal:who")) {
                lookUpPlayer(event, capitalize(value(event, "name")));
                return;
            }
            if (id.equals("modal:prof:addchar")) {
                show(event, null, pickProfessionScreen(capitalize(value(event, "name"))));
                return;
            }
            if (id.equals("modal:prof:removechar")) {
                show(event, null, removeProfessionScreen(event, capitalize(value(event, "name"))));
                return;
            }
            if (id.startsWith("modal:prof:skill:")) {
                // modal:prof:skill:<character>:<profession>
                String[] parts = id.substring("modal:prof:skill:".length()).split(":", 2);
                show(event, addProfession(event, parts[0], parts[1], value(event, "skill")), professionsScreen(event));
                return;
            }
            if (id.startsWith("modal:admin:")) {
                String action = id.substring("modal:admin:".length());
                String name = capitalize(value(event, "name"));
                String problem = validateAdminAction(action, name);
                if (problem != null) {
                    show(event, problem, adminScreen());
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
        List<String> mine = linkedCharacters(event.getUser().getId());
        if (mine.isEmpty()) {
            lines.add("Your Characters: ❌ **None Linked**");
            lines.add("");
            lines.add("Use `/help` to see how to link your characters.");
        } else {
            lines.add("Your Characters: **" + String.join("**, **", mine) + "**");
        }

        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.success("menu:online", "Who's Online"));
        buttons.add(Button.primary("menu:who", "Look Up Player"));
        buttons.add(Button.primary("menu:prof", "Professions"));
        if (isAdmin(event)) buttons.add(Button.primary("menu:admin", "Admin Actions"));

        return new Screen(embed("WoWChat Menu", String.join("\n", lines)), rows(buttons));
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

    private static void lookUpPlayer(ModalInteractionEvent event, String name) {
        GamePacketHandler game = game();
        if (game == null) {
            show(event, null, new Screen(embed("Look Up Player", NOT_CONNECTED), backOnly()));
            return;
        }

        event.deferEdit().queue();
        event.getHook().editOriginal(editData(null, new Screen(
            embed("Look Up Player", "🔎 Looking up **" + name + "**…"), backOnly()))).queue();

        // The game answers later through GamePacketHandler.handle_SMSG_WHO, which calls reply
        AtomicBoolean answered = new AtomicBoolean(false);
        List<ActionRow> resultRows = Arrays.asList(
            ActionRow.of(postButton(), Button.primary("menu:who", "Look Up Another")),
            backRow());
        Consumer<String> reply = result -> {
            if (!answered.compareAndSet(false, true)) return;
            event.getHook().editOriginal(editData(null, new Screen(embed("Look Up Player", result), resultRows))).queue();
        };
        CommandHandler$.MODULE$.whoRequest_$eq(new WhoRequest(name, reply));
        scheduler.schedule(() -> {
            if (!answered.compareAndSet(false, true)) return;
            event.getHook().editOriginal(editData(null, new Screen(
                embed("Look Up Player", "❌ The WoW server didn't answer about **" + name + "**. Try again."), resultRows))).queue();
        }, WHO_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        game.sendWho(name);
    }

    private static Screen listsScreen() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ignored players' messages aren't relayed to Discord. ")
          .append("Banned players are also kicked from the guild automatically.\n\n");
        appendNameList(sb, "Ignored", scala.collection.JavaConverters.seqAsJavaList(IgnoreManager.getIgnoredPlayers()));
        sb.append("\n");
        appendNameList(sb, "Banned", BanManager.getBannedPlayers());
        return new Screen(embed("Ignore & Ban Lists", sb.toString()), postAndBackRow("menu:admin"));
    }

    private static void appendNameList(StringBuilder sb, String title, List<String> names) {
        sb.append("**").append(title).append(" (").append(names.size()).append(")**\n");
        if (names.isEmpty()) sb.append("Nobody\n");
        for (String n : names) sb.append("- ").append(capitalize(n)).append("\n");
    }

    // -------------------------------------------------------------------------
    // Professions
    // -------------------------------------------------------------------------

    private static Screen professionsScreen(IReplyCallback event) {
        List<String> mine = linkedCharacters(event.getUser().getId());
        boolean admin = isAdmin(event);
        boolean anyProfession = false;

        StringBuilder sb = new StringBuilder();
        if (mine.isEmpty()) {
            sb.append("You have no linked characters yet. Use `/help` to see how to link them.\n");
        } else {
            for (String c : mine) {
                List<String> profs = ProfessionManager.getProfessions(c);
                anyProfession |= !profs.isEmpty();
                sb.append("**").append(c).append("**: ").append(profs.isEmpty() ? "No professions yet" : formatAll(profs)).append("\n");
            }
        }
        sb.append("\nEach character can have up to ").append(ProfessionManager.MAX_PROFESSIONS)
          .append(" professions. They show in the guild roster, and in **Find Crafters**.");

        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
            Button.success("menu:prof:add", "Add / Update").withDisabled(mine.isEmpty() && !admin),
            Button.danger("menu:prof:remove", "Remove").withDisabled(!anyProfession && !admin),
            Button.primary("menu:prof:find", "Find Crafters")));
        rows.add(backRow());
        return new Screen(embed("Professions", sb.toString()), rows);
    }

    private static Screen pickCharacterScreen(IReplyCallback event, String selectId, String title, String prompt) {
        StringSelectMenu.Builder select = StringSelectMenu.create(selectId).setPlaceholder("Choose a character");
        for (String c : limit(linkedCharacters(event.getUser().getId()), isAdmin(event) ? PAGE_SIZE - 1 : PAGE_SIZE)) {
            select.addOption(c, c);
        }
        if (isAdmin(event)) select.addOption("Another character…", OTHER, "Type any guild member's name (admin)");
        return new Screen(embed(title, prompt), Arrays.asList(ActionRow.of(select.build()), backRow("menu:prof")));
    }

    private static Screen pickProfessionScreen(String character) {
        List<String> current = new ArrayList<>();
        for (String p : ProfessionManager.getProfessions(character)) current.add(ProfessionManager.profName(p));

        StringSelectMenu.Builder select = StringSelectMenu.create("menu:prof:addprof:" + character).setPlaceholder("Choose a profession");
        for (String p : ProfessionManager.VALID_PROFESSIONS) {
            if (current.contains(p)) select.addOption(p, p, "Already has it: update the skill level");
            else select.addOption(p, p);
        }
        return new Screen(embed("Add / Update Profession", "Which profession for **" + character + "**?"),
            Arrays.asList(ActionRow.of(select.build()), backRow("menu:prof")));
    }

    private static String addProfession(IReplyCallback event, String character, String profession, String skillText) {
        Integer skill = null;
        if (!skillText.isEmpty()) {
            try {
                skill = Integer.parseInt(skillText);
            } catch (NumberFormatException e) {
                return "❌ The skill level must be a number between 1 and 450.";
            }
        }
        String error = ProfessionManager.add(character, profession, skill, event.getUser().getId(), event.getGuild());
        if (error != null) return "❌ " + error;
        return "✅ Added **" + profession + (skill != null ? " (" + skill + ")" : "") + "** for **" + character + "**.";
    }

    // character null: the caller's own characters, otherwise that one character (admin typed a name)
    private static Screen removeProfessionScreen(IReplyCallback event, String character) {
        List<String> characters = character == null ? linkedCharacters(event.getUser().getId()) : Collections.singletonList(character);
        boolean admin = isAdmin(event) && character == null;

        StringSelectMenu.Builder select = StringSelectMenu.create("menu:prof:removesel").setPlaceholder("Choose a profession to remove");
        int count = 0;
        for (String c : characters) {
            for (String stored : ProfessionManager.getProfessions(c)) {
                if (count >= PAGE_SIZE - 1) break;
                select.addOption(c + ": " + ProfessionManager.formatProfession(stored), c + ":" + ProfessionManager.profName(stored));
                count++;
            }
        }
        if (admin) select.addOption("Another character…", OTHER, "Type any guild member's name (admin)");

        if (select.getOptions().isEmpty()) {
            String who = character == null ? "Your characters have" : "**" + character + "** has";
            return new Screen(embed("Remove Profession", who + " no professions to remove."), Collections.singletonList(backRow("menu:prof")));
        }
        return new Screen(embed("Remove Profession", "Which profession do you want to remove?"),
            Arrays.asList(ActionRow.of(select.build()), backRow("menu:prof")));
    }

    private static Screen findCraftersPickScreen() {
        StringSelectMenu.Builder select = StringSelectMenu.create("menu:prof:findsel").setPlaceholder("Choose a profession");
        for (String p : ProfessionManager.VALID_PROFESSIONS) select.addOption(p, p);
        return new Screen(embed("Find Crafters", "Which profession are you looking for?"),
            Arrays.asList(ActionRow.of(select.build()), backRow("menu:prof")));
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
        return new Screen(embed(profession + " (" + results.size() + ")", sb.toString()), postAndBackRow("menu:prof"));
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
            "**Promote / Demote**: move a guild member one rank up or down in-game.",
            "**Ignore**: stop relaying a player's messages to Discord.",
            "**Ban**: ignore a player and kick them from the guild automatically.",
            "**Unignore / Unban**: pick someone from the list to remove them.",
            "**Ignore & Ban Lists**: see everyone on both lists.");
        List<ActionRow> rows = Arrays.asList(
            ActionRow.of(
                Button.success("menu:admin:ask:promote", "Promote"),
                Button.danger("menu:admin:ask:demote", "Demote")),
            ActionRow.of(
                Button.primary("menu:admin:ask:ignore", "Ignore"),
                Button.secondary("menu:admin:list:unignore:0", "Unignore"),
                Button.danger("menu:admin:ask:ban", "Ban"),
                Button.secondary("menu:admin:list:unban:0", "Unban"),
                Button.primary("menu:admin:lists", "Ignore & Ban Lists")),
            backRow());
        return new Screen(embed("Admin Actions", description), rows);
    }

    // Returns an error message, or null when the action can go ahead
    private static String validateAdminAction(String action, String name) {
        if (action.equals("ignore") && IgnoreManager.isIgnored(name)) return "ℹ️ **" + name + "** is already on the ignore list.";
        if (action.equals("ban") && BanManager.isBanned(name)) return "ℹ️ **" + name + "** is already on the ban list.";
        if (!action.equals("promote") && !action.equals("demote")) return null;

        GamePacketHandler game = game();
        if (game == null) return NOT_CONNECTED;
        GuildMember target = findMember(game, name);
        if (target == null) return "❌ **" + name + "** isn't in the guild.";
        GuildMember bot = findMember(game, wowchat.common.Global$.MODULE$.config().wow().character());
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
                GuildMember target = game == null ? null : findMember(game, name);
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
            Collections.singletonList(ActionRow.of(yes, Button.secondary("menu:admin", "Cancel"))));
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
                return IgnoreManager.ignore(name)
                    ? "✅ **" + name + "** is now ignored. Their messages won't be relayed to Discord."
                    : "ℹ️ **" + name + "** is already on the ignore list.";
            case "unignore":
                return IgnoreManager.unignore(name)
                    ? "✅ **" + capitalize(name) + "** is no longer ignored."
                    : "ℹ️ **" + capitalize(name) + "** isn't on the ignore list.";
            case "ban":
                return BanManager.ban(name)
                    ? "✅ **" + name + "** is now banned. They'll be kicked from the guild on the next check."
                    : "ℹ️ **" + name + "** is already on the ban list.";
            case "unban":
                return BanManager.unban(name)
                    ? "✅ **" + capitalize(name) + "** is no longer banned."
                    : "ℹ️ **" + capitalize(name) + "** isn't on the ban list.";
            default:
                return "❌ Unknown action.";
        }
    }

    // Select menu of who's on the ignore or ban list, 25 per page. Null when the list is empty.
    private static Screen removeFromListScreen(String action, int page) {
        List<String> names = action.equals("unban")
            ? BanManager.getBannedPlayers()
            : scala.collection.JavaConverters.seqAsJavaList(IgnoreManager.getIgnoredPlayers());
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
        rows.add(backRow("menu:admin"));
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
        // Follows guildDiscordLinking.noteLocation: members can't edit officer notes themselves
        String noteStep = "officer".equals(DiscordIdExtractor.getNoteLocation())
            ? "3. Ask a guild officer to paste it in your character's **Officer Note** in WoW."
            : "3. In WoW, open the Guild panel and paste it in your character's **Public Note**.";
        return new EmbedBuilder()
            .setColor(EMBED_COLOR)
            .setTitle("WoWChat Discord Bot", GITHUB_URL)
            .setDescription("Connects your WoW guild to Discord: chat goes both ways, and guild panels update themselves.")
            .addField("`/menu`", String.join("\n",
                "Opens the bot menu. Only you can see it.",
                "- **Who's Online**: who's playing right now, with level, class and zone.",
                "- **Look Up Player**: level, class, zone and guild rank of any player.",
                "- **Professions**: add your characters' professions, or find a crafter.",
                "- **Admin Actions** *(bot admins)*: promote, demote, ignore and ban, and see the ignore and ban lists.",
                "Most views have **Post to channel** to share them."), false)
            .addField("Link your characters", String.join("\n",
                "1. In Discord: **User Settings → Advanced → Developer Mode** on.",
                "2. Right-click your name, **Copy User ID**.",
                noteStep,
                "Linked characters show your Discord name in the guild panels, and let you set their professions."), false)
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

    private static Modal skillModal(String character, String profession) {
        TextInput input = TextInput.create("skill", "Skill level (1-450, optional)", TextInputStyle.SHORT)
            .setRequired(false).setMaxLength(3).build();
        return Modal.create("modal:prof:skill:" + character + ":" + profession, profession + " for " + character)
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

    // Bot owner, server owner, or a member with one of discord.commandRoleIds
    private static boolean isAdmin(IReplyCallback event) {
        String userId = event.getUser().getId();
        if (userId.equals(ownerId)) return true;
        Guild guild = event.getGuild();
        if (guild != null && userId.equals(guild.getOwnerId())) return true;
        Member member = event.getMember();
        return ProfessionManager.hasCommandRole(member);
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

    // Characters whose guild note holds this Discord user's ID, sorted by name
    private static List<String> linkedCharacters(String userId) {
        List<String> names = new ArrayList<>();
        for (GuildMember m : roster()) {
            if (userId.equals(DiscordIdExtractor.extractDiscordId(m))) names.add(m.name());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String discordIdOf(String character) {
        for (GuildMember m : roster()) {
            if (m.name().equalsIgnoreCase(character)) return DiscordIdExtractor.extractDiscordId(m);
        }
        return null;
    }

    private static GuildMember findMember(GamePacketHandler game, String name) {
        scala.collection.Iterator<GuildMember> it = game.getGuildRoster().valuesIterator();
        while (it.hasNext()) {
            GuildMember m = it.next();
            if (m.name().equalsIgnoreCase(name)) return m;
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
