/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command;

import client.Client;
import client.command.commands.gm0.*;
import client.command.commands.gm1.BuffMeCommand;
import client.command.commands.gm2.*;
import client.command.commands.gm3.*;
import client.command.commands.gm4.*;
import client.command.commands.gm5.*;
import client.command.commands.gm6.*;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CommandsExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommandsExecutor.class);
    private static final CommandsExecutor instance = new CommandsExecutor();
    private static final char USER_HEADING = '@';
    private static final char GM_HEADING = '!';

    private final HashMap<String, Command> registeredCommands = new HashMap<>();
    private final List<Pair<List<String>, List<String>>> commandsNameDesc = new ArrayList<>();
    private Pair<List<String>, List<String>> levelCommandsCursor;

    public static CommandsExecutor getInstance() {
        return instance;
    }

    public static boolean isCommand(Client client, String content) {
        char heading = content.charAt(0);
        if (client.getPlayer().isGM()) {
            return heading == USER_HEADING || heading == GM_HEADING;
        }
        return heading == USER_HEADING;
    }

    private CommandsExecutor() {
        registerLv0Commands();
        registerLv1Commands();
        registerLv2Commands();
        registerLv3Commands();
        registerLv4Commands();
        registerLv5Commands();
        registerLv6Commands();
    }

    /* Tests: whether a command name is registered. */
    boolean hasCommand(String name) {
        return registeredCommands.containsKey(name);
    }

    /* Tests: a registered command's GM rank, or -1. */
    int rankForTest(String name) {
        Command c = registeredCommands.get(name);
        return c == null ? -1 : c.getRank();
    }

    /* Tests: the class a command name runs, or null. */
    Class<?> classForTest(String name) {
        Command c = registeredCommands.get(name);
        return c == null ? null : c.getClass();
    }

    /* Tests: a fresh registry built under the current system properties. */
    static CommandsExecutor freshForTest() {
        return new CommandsExecutor();
    }

    public List<Pair<List<String>, List<String>>> getGmCommands() {
        return commandsNameDesc;
    }

    public void handle(Client client, String message) {
        if (client.tryacquireClient()) {
            try {
                handleInternal(client, message);
            } finally {
                client.releaseClient();
            }
        } else {
            client.getPlayer().dropMessage(5, "Try again in a while... Latest commands are currently being processed.");
        }
    }

    private void handleInternal(Client client, String message) {
        if (client.getPlayer().getMapId() == MapId.JAIL) {
            client.getPlayer().yellowMessage("You do not have permission to use commands while in jail.");
            return;
        }
        final String splitRegex = "[ ]";
        String[] splitedMessage = message.substring(1).split(splitRegex, 2);
        if (splitedMessage.length < 2) {
            splitedMessage = new String[]{splitedMessage[0], ""};
        }

        client.getPlayer().setLastCommandMessage(splitedMessage[1]);    // thanks Tochi & Nulliphite for noticing string messages being marshalled lowercase
        final String commandName = splitedMessage[0].toLowerCase();
        final String[] lowercaseParams = splitedMessage[1].toLowerCase().split(splitRegex);

        final Command command = registeredCommands.get(commandName);
        if (command == null) {
            client.getPlayer().yellowMessage("Command '" + commandName + "' is not available. See @commands for a list of available commands.");
            return;
        }
        if (client.getPlayer().gmLevel() < command.getRank()) {
            audit(client, commandName, splitedMessage[1], command.getRank(), "denied");
            client.getPlayer().yellowMessage("You do not have permission to use this command.");
            return;
        }
        String[] params;
        if (lowercaseParams.length > 0 && !lowercaseParams[0].isEmpty()) {
            params = Arrays.copyOfRange(lowercaseParams, 0, lowercaseParams.length);
        } else {
            params = new String[]{};
        }

        try {
            command.execute(client, params);
        } catch (RuntimeException e) {
            audit(client, commandName, splitedMessage[1], command.getRank(), "error");
            throw e;
        }
        audit(client, commandName, splitedMessage[1], command.getRank(), "ok");
        log.info("Chr {} used command {}", client.getPlayer().getName(), command.getClass().getSimpleName());
    }

    /**
     * GM command audit (ticket 43): every command above player rank, and any command a GM character runs, goes to
     * the server log here and to the {@code gm_command_audit} table off the game thread ({@link GmAudit}).
     */
    private static void audit(Client client, String commandName, String args, int rank, String outcome) {
        var player = client.getPlayer();
        if (rank <= 0 && player.gmLevel() <= 0) {
            return;
        }
        log.info("GM audit: {} (account {}, gm {}) {} !{} {} on map {} ch {}", player.getName(),
                client.getAccountName(), player.gmLevel(), outcome, commandName, args, player.getMapId(),
                client.getChannel());
        GmAudit.record(new GmAudit.Entry(System.currentTimeMillis(), client.getAccID(), client.getAccountName(),
                player.getId(), player.getName(), player.gmLevel(), commandName, args, rank, player.getMapId(),
                client.getChannel(), client.getRemoteAddress(), outcome));
    }

    private void addCommandInfo(String name, Class<? extends Command> commandClass) {
        try {
            levelCommandsCursor.getRight().add(commandClass.getDeclaredConstructor().newInstance().getDescription());
            levelCommandsCursor.getLeft().add(name);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addCommand(String[] syntaxs, Class<? extends Command> commandClass) {
        for (String syntax : syntaxs) {
            addCommand(syntax, 0, commandClass);
        }
    }

    private void addCommand(String syntax, Class<? extends Command> commandClass) {
        //for (String syntax : syntaxs){
        addCommand(syntax, 0, commandClass);
        //}
    }

    private void addCommand(String[] surtaxes, int rank, Class<? extends Command> commandClass) {
        for (String syntax : surtaxes) {
            addCommand(syntax, rank, commandClass);
        }
    }

    private void addCommand(String syntax, int rank, Class<? extends Command> commandClass) {
        if (registeredCommands.containsKey(syntax.toLowerCase())) {
            log.warn("Error on register command with name: {}. Already exists.", syntax);
            return;
        }

        String commandName = syntax.toLowerCase();
        addCommandInfo(commandName, commandClass);

        try {
            Command commandInstance = commandClass.getDeclaredConstructor().newInstance();     // thanks Halcyon for noticing commands getting reinstanced every call
            commandInstance.setRank(rank);

            registeredCommands.put(commandName, commandInstance);
        } catch (Exception e) {
            log.warn("Failed to create command instance", e);
        }
    }

    private void registerLv0Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand(new String[]{"help", "commands"}, HelpCommand.class);
        addCommand(new String[]{"daily", "checkin"}, DailyCommand.class);
        //addCommand(new String[]{"starforce", "sf"}, StarForceCommand.class);
        addCommand("worldchat", WorldChatCommand.class);
        addCommand("bp", BackPackCommand.class);
        addCommand("backpack", BackPackToggleCommand.class);
        addCommand("gfx", GfxCommand.class);
        addCommand("bank", BankCommand.class);
        addCommand("check", CheckCommand.class);
        addCommand("droplimit", DropLimitCommand.class);
       // addCommand("craft", CraftCommand.class);
        addCommand("time", TimeCommand.class);
        addCommand("coupons", CouponsCommand.class);   // 1.5.1: when your EXP/drop coupons work (server and Pacific time)
        addCommand("credits", StaffCommand.class);
        addCommand("minesweeper", MineSweeperCommand.class);
        addCommand("memory", MemoryCommand.class);
        addCommand("dps", DamageCheckCommand.class);
        addCommand(new String[]{"qs", "qsell"}, QuickSellCommand.class);
        addCommand(new String[]{"bb", "buyback"}, BuyBackCommand.class);
        addCommand("uptime", UptimeCommand.class);
        addCommand("gacha", GachaCommand.class);   // 1.5.0: prizes and odds per machine
        addCommand("exchange", ExchangeCommand.class);
        addCommand(new String[] {"elb", "expeditionleaderboard"}, ExpeditionLeaderboardCommand.class);
        addCommand("dispose", DisposeCommand.class);
        addCommand("changel", ChangeLanguageCommand.class);
        addCommand("equiplv", EquipLvCommand.class);
        addCommand("showrates", ShowRatesCommand.class);
        addCommand("rates", RatesCommand.class);
        addCommand("online", OnlineCommand.class);
        // "weblogin" is not registered: the owner dropped it for 1.6.0 (2026-10-26); the site and launcher sign in with
        // the game username and password (ticket 105). WebLoginCommand stays for its revoked.txt check.
        addCommand("gm", GmCommand.class);
        addCommand(new String[]{"bug", "reportbug"}, ReportBugCommand.class);
        addCommand("idea", ReportBugCommand.Idea.class);
        addCommand("points", ReadPointsCommand.class);
        addCommand("joinevent", JoinEventCommand.class);
        addCommand("leaveevent", LeaveEventCommand.class);
        addCommand("ranks", RanksCommand.class);
        addCommand("str", StatStrCommand.class);
        addCommand("dex", StatDexCommand.class);
        addCommand("int", StatIntCommand.class);
        addCommand("luk", StatLukCommand.class);
        addCommand("enableauth", EnableAuthCommand.class);
        addCommand("toggleexp", ToggleExpCommand.class);
        addCommand("mylawn", MapOwnerClaimCommand.class);
        addCommand("bosshp", BossHpCommand.class);
        addCommand("mobhp", MobHpCommand.class);
        addCommand("roll", RollCommand.class);
        addCommand("quiet", QuietCommand.class);
        addCommand("botme", BotMeCommand.class);
        addCommand("dismiss", DismissCommand.class);
        addCommand("botdrama", client.command.commands.gm0.BotDramaCommand.class);   // ticket 58
        addCommand("romance", client.command.commands.gm0.RomanceCommand.class);   // 1.5.0 bot drama: romance opt-in
        addCommand("guildwar", client.command.commands.gm0.GuildWarCommand.class);   // 1.5.0 bot drama: guild wars
        addCommand("price", client.command.commands.gm0.PriceCommand.class);   // 1.5.0 rc3: the price board
        addCommand("settings", client.command.commands.gm0.SettingsCommand.class);   // 1.5.0: bot settings menu
        addCommand(new String[]{"guide", "tutorial"}, client.command.commands.gm0.GuideCommand.class);   // 1.5.0: beginner's guide window (@help stays the command list)
        addCommand("goto", 0, GotoCommand.class);   // 1.5.0 (friends asked): players hop between towns; GotoCommand limits non-GMs to GOTO_TOWNS and blocks events, dungeons and no-migrate maps
	    addCommand("shop", ShopCommand.class);
       // addCommand("damageskin",  DamageSkinCommand.class);
	    /*
         addCommand("sell", SellitemsCommand.class);
         addCommand("resetap", ResetAPStatCommand.class);
         addCommand("resetsp", ResetSPStatCommand.class);
        */
        addCommand(new String[]{"wdf", "whatdropsfrom"}, WhatDropsFromCommand.class);
        addCommand(new String[]{"wd", "whodrops"}, WhoDropsCommand.class);
        //addCommand("orebag", OreBagCommand.class);
        // 1.5.1 security: @autologin removed (it let anyone on the same IP log into any account without a password)
        addCommand("weekly", BossLogCommand.class);
        commandsNameDesc.add(levelCommandsCursor);
    }


    private void registerLv1Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        /*addCommand("whatdropsfrom", 1, WhatDropsFromCommand.class);
        addCommand("whodrops", 1, WhoDropsCommand.class);
        addCommand("goto", 1, GotoCommand.class);*/
        addCommand("buffme", 1, BuffMeCommand.class); // 1.4.2: the GM-1 tier reshuffle dropped it; Cosmic has it here
        addCommand("rate", 1, client.command.commands.gm1.RateCommand.class); // ticket 59: rate a bot's last line

        commandsNameDesc.add(levelCommandsCursor);
    }


    private void registerLv2Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("recharge", 2, RechargeCommand.class);
        addCommand("whereami", 2, WhereaMiCommand.class);
        addCommand("hide", 2, HideCommand.class);
        addCommand("unhide", 2, UnHideCommand.class);
        addCommand("sp", 2, SpCommand.class);
        addCommand("ap", 2, ApCommand.class);
        addCommand("empowerme", 2, EmpowerMeCommand.class);
        addCommand("buffmap", 2, BuffMapCommand.class);
        addCommand("buff", 2, BuffCommand.class);
        addCommand("bomb", 2, BombCommand.class);
        addCommand("dc", 2, DcCommand.class);
        addCommand("cleardrops", 2, ClearDropsCommand.class);
        addCommand("clearslot", 2, ClearSlotCommand.class);
        addCommand("clearsavelocs", 2, ClearSavedLocationsCommand.class);
        addCommand("warp", 2, WarpCommand.class);
        addCommand(new String[]{"warphere", "summon"}, 2, SummonCommand.class);
        addCommand(new String[]{"warpto", "reach", "follow"}, 2, ReachCommand.class);
        addCommand("gmshop", 2, GmShopCommand.class);
        addCommand("heal", 2, HealCommand.class);
        addCommand("item", 2, ItemCommand.class);
        addCommand("drop", 2, ItemDropCommand.class);
        addCommand("level", 2, LevelCommand.class);
        addCommand("levelpro", 2, LevelProCommand.class);
        addCommand("setslot", 2, SetSlotCommand.class);
        addCommand("setstat", 2, SetStatCommand.class);
        addCommand("maxstat", 2, MaxStatCommand.class);
        addCommand("resetskill", 2, ResetSkillCommand.class);
        addCommand(new String[]{"search", "find"}, 2, SearchCommand.class);
        addCommand("jail", 2, JailCommand.class);
        addCommand("unjail", 2, UnJailCommand.class);
        addCommand("job", 2, JobCommand.class);
        addCommand("unbug", 2, UnBugCommand.class);
        addCommand("id", 2, IdCommand.class);
        addCommand("gachalist", GachaListCommand.class);
        addCommand("loot", 2,  LootCommand.class);
        addCommand("mobskill", 2, MobSkillCommand.class);
        addCommand("maxskill", 2, MaxSkillCommand.class);
        if (soloMapling.server.AttackCapture.isEnabled()) {
            addCommand("classtest", 2, ClassTestCommand.class); // test server attack capture (ticket 32)
            addCommand("botshowcase", 3, client.command.commands.gm3.BotShowcaseCommand.class); // ticket 32
            addCommand("botground", 3, client.command.commands.gm3.BotGroundCommand.class); // 1.5.0 rc5: bots off their foothold
        }
        if (soloMapling.qa.QaSuite.isEnabled()) {
            addCommand("qa", 3, client.command.commands.gm3.QaCommand.class); // test scenarios + logs/qa (docs/ops/qa-suite.md)
            addCommand("botcombat", 3, client.command.commands.gm3.BotCombatCommand.class); // 1.5.0 rc3 bot combat staging
            addCommand("godlydrop", 3, client.command.commands.gm3.GodlyDropCommand.class); // 1.5.2 godly drop staging
        }
        if (client.command.commands.gm3.BotWhyCommand.isEnabled()) {
            addCommand("botwhy", 3, client.command.commands.gm3.BotWhyCommand.class);
        }
        if (soloMapling.autoqa.AutoQa.isEnabled()) {
            addCommand("autoqa", 3, client.command.commands.gm3.AutoQaCommand.class); // health sweeps + check runner (docs/ops/qa-suite.md)
        }
        if (soloMapling.qa.QaSuite.isEnabled() || soloMapling.autoqa.AutoQa.isEnabled()) {
            addCommand("botwatch", 3, client.command.commands.gm3.BotWatchCommand.class); // 1.6.0: bots do what they decided
        }
        if (soloMapling.autoqa.AutoQa.isEnabled() || System.getProperty(soloMapling.autoqa.SocialTrace.PROPERTY) != null) {
            addCommand("socialtrace", 3, client.command.commands.gm3.SocialTraceCommand.class); // 1.6.0 bot social trace
        }
        addCommand("editShop", 2,  EditShopCommand.class);
        addCommand("board", 2, client.command.commands.gm2.BoardCommand.class); // 1.6.0 site Buy/Sell board: list, remove a post

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv3Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("debuff", 3, DebuffCommand.class);
        addCommand("mobdrop", 3, MobDropCommand.class);
        addCommand("make", 3, MakeCommand.class);
        addCommand("fly", 3, FlyCommand.class);
        addCommand("spy", 3, SpyPlayerCommand.class);
        if (soloMapling.server.MapleRootSolo.isEnabled()) {
            addCommand("bot", 4, client.command.commands.gm4.ArtificialPlayerCommand.class);
            addCommand("opq", 4, client.command.commands.gm4.OPQCommands.class);
            addCommand("reactor", 4, client.command.commands.gm4.ReactorCommands.class);
            addCommand("move", 4, client.command.commands.gm4.BotMoveCommand.class);
            addCommand("gcmove", 4, client.command.commands.gm4.GCMoveCommand.class);
            addCommand("betafmshop", 4, client.command.commands.gm4.ArtificialFreeMarketCommand.class);
            addCommand("test", 4, client.command.commands.gm4.TestDevCommand.class);
            addCommand("fmbot", 4, client.command.commands.gm4.FMBotCommand.class);
            addCommand("tradebot", 4, client.command.commands.gm4.TradeBotTestCommand.class);
            addCommand("env", 4, client.command.commands.gm4.EnvironmentCommand.class);
            addCommand("roster", 4, client.command.commands.gm4.RosterCommand.class);
            addCommand("brain", 4, client.command.commands.gm4.BrainCommand.class);
            addCommand("botyield", 4, client.command.commands.gm4.BotYieldCommand.class);
            addCommand("botscroll", 4, client.command.commands.gm4.BotScrollCommand.class);   // ticket 11
            addCommand("botwealth", 4, client.command.commands.gm4.BotWealthCommand.class);   // ticket 56B
            addCommand("economy", 4, client.command.commands.gm4.EconomyCommand.class);   // 1.5.0 rc3 economy lane
            addCommand(new String[]{"event", "worldevent"}, 4, client.command.commands.gm4.WorldEventCommand.class);
            addCommand("affinity", 4, client.command.commands.gm4.AffinityCommand.class);   // ticket 58
            addCommand("couple", 4, client.command.commands.gm4.CoupleCommand.class);   // ticket 58
            addCommand("botromance", 4, client.command.commands.gm4.BotRomanceCommand.class);   // 1.5.0 bot drama
            addCommand("botrival", 4, client.command.commands.gm4.BotRivalCommand.class);   // 1.5.0 bot drama
            addCommand("botwar", 4, client.command.commands.gm4.BotWarCommand.class);   // 1.5.0 bot drama
            addCommand("botguild", 4, client.command.commands.gm4.BotGuildCommand.class);   // 1.5.0 bot guilds
            addCommand("botmapcap", 4, client.command.commands.gm4.BotMapCapCommand.class);   // 1.5.0 per-map bot cap
        }
        addCommand("gacharoll", 3, client.command.commands.gm4.GachaRollCommand.class);   // 1.5.0 gachapon
        addCommand("gachasim", 3, client.command.commands.gm4.GachaSimCommand.class);
        addCommand("spawn", 3, SpawnCommand.class);
        addCommand("mutemap", 3, MuteMapCommand.class);
        addCommand("checkdmg", 3, CheckDmgCommand.class);
        addCommand("inmap", 3, InMapCommand.class);
        addCommand("reloadevents", 3, ReloadEventsCommand.class);
        addCommand("reloaddrops", 3, ReloadDropsCommand.class);
        addCommand("reloadportals", 3, ReloadPortalsCommand.class);
        addCommand("reloadmap", 3, ReloadMapCommand.class);
        addCommand("bounce", 3, client.command.commands.gm3.BounceCommand.class); // bounce block zones (bounce-zones.yaml)
        addCommand("reloadshops", 3, ReloadShopsCommand.class);
        addCommand("reloadcashshop", 3, ReloadCashShopCommand.class);
        addCommand("hpmp", 3, HpMpCommand.class);
        addCommand("maxhpmp", 3, MaxHpMpCommand.class);
        addCommand("setmaxhp", 3, SetMaxHpCommand.class); // 1.6.0: stage MaxHP/MaxMP past 32,767 for the 4-byte client
        addCommand("music", 3, MusicCommand.class);
        addCommand("monitor", 3, MonitorCommand.class);
        addCommand("monitors", 3, MonitorsCommand.class);
        addCommand("ignore", 3, IgnoreCommand.class);
        addCommand("ignored", 3, IgnoredCommand.class);
        addCommand("pos", 3, PosCommand.class);
        addCommand("togglecoupon", 3, ToggleCouponCommand.class);
        addCommand("togglewhitechat", 3, ChatCommand.class);
        addCommand("fame", 3, FameCommand.class);
        addCommand("givenx", 3, GiveNxCommand.class);
        addCommand("givevp", 3, GiveVpCommand.class);
        addCommand("givems", 3, GiveMesosCommand.class);
        addCommand("giverp", 3, GiveRpCommand.class);
        addCommand("expeds", 3, ExpedsCommand.class);
        addCommand("kill", 3, KillCommand.class);
        addCommand("seed", 3, SeedCommand.class);
        addCommand("maxenergy", 3, MaxEnergyCommand.class);
        addCommand("sense", 3, SenseCommand.class);
        addCommand("killall", 3, KillAllCommand.class);
        addCommand("notice", 3, NoticeCommand.class);
        addCommand("rip", 3, RipCommand.class);
        addCommand("openportal", 3, OpenPortalCommand.class);
        addCommand("closeportal", 3, ClosePortalCommand.class);
        addCommand("pe", 3, PeCommand.class);
        addCommand("startevent", 3, StartEventCommand.class);
        addCommand("endevent", 3, EndEventCommand.class);
        addCommand("startmapevent", 3, StartMapEventCommand.class);
        addCommand("stopmapevent", 3, StopMapEventCommand.class);
        addCommand("online2", 3, OnlineTwoCommand.class);
        addCommand("ban", 3, BanCommand.class);
        addCommand("unban", 3, UnBanCommand.class);
        addCommand("invite", 3, client.command.commands.gm3.InviteCommand.class); // ticket 105: site sign-up codes
        addCommand("resetpw", 3, client.command.commands.gm3.ResetPwCommand.class); // ticket 107: GM-approved password resets
        addCommand("healmap", 3, HealMapCommand.class);
        addCommand("healperson", 3, HealPersonCommand.class);
        addCommand("hurt", 3, HurtCommand.class);
        addCommand("killmap", 3, KillMapCommand.class);
        addCommand("night", 3, NightCommand.class);
        if (Boolean.getBoolean("weather.enabled")) {
            addCommand("weather", 2, client.command.commands.gm2.WeatherCommand.class);
            addCommand("lamp", 3, client.command.commands.gm3.LampCommand.class);
        }
        addCommand("npc", 3, NpcCommand.class);
        addCommand("face", 3, FaceCommand.class);
        addCommand("hair", 3, HairCommand.class);
        addCommand("startquest", 3, QuestStartCommand.class);
        addCommand("completequest", 3, QuestCompleteCommand.class);
        addCommand("resetquest", 3, QuestResetCommand.class);
        addCommand("timer", 3, TimerCommand.class);
        addCommand("timermap", 3, TimerMapCommand.class);
        addCommand("timerall", 3, TimerAllCommand.class);
        addCommand("warpmap", 3, WarpMapCommand.class);
        addCommand("warparea", 3, WarpAreaCommand.class);

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv4Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("econstats", 4, client.command.commands.gm4.EconStatsCommand.class);   // 1.6.0 Track D economy stats snapshot
        addCommand("servermessage", 4, ServerMessageCommand.class);
        addCommand("proitem", 4, ProItemCommand.class);
        addCommand("seteqstat", 4, SetEqStatCommand.class);
        addCommand("exprate", 4, ExpRateCommand.class);
        addCommand("mesorate", 4, MesoRateCommand.class);
        addCommand("droprate", 4, DropRateCommand.class);
        addCommand("bossdroprate", 4, BossDropRateCommand.class);
        addCommand("questrate", 4, QuestRateCommand.class);
        addCommand("travelrate", 4, TravelRateCommand.class);
        addCommand("fishrate", 4, FishingRateCommand.class);
        addCommand("itemvac", 4, ItemVacCommand.class);
        addCommand("forcevac", 4, ForceVacCommand.class);
        addCommand("zakum", 4, ZakumCommand.class);
        addCommand("zakumpq", 4, ZakumPqCommand.class);
        addCommand("horntail", 4, HorntailCommand.class);
        addCommand("pinkbean", 4, PinkbeanCommand.class);
        addCommand("pap", 4, PapCommand.class);
        addCommand("pianus", 4, PianusCommand.class);
        addCommand("cake", 4, CakeCommand.class);
        addCommand("playernpc", 4, PlayerNpcCommand.class);
        addCommand("playernpcremove", 4, PlayerNpcRemoveCommand.class);
        addCommand("pnpc", 4, PnpcCommand.class);
        addCommand("pnpcremove", 4, PnpcRemoveCommand.class);
        addCommand("pmob", 4, PmobCommand.class);
        addCommand("pmobremove", 4, PmobRemoveCommand.class);

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv5Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());

        addCommand("debug", 5, DebugCommand.class);
        addCommand("set", 5, SetCommand.class);
        addCommand("showpackets", 5, ShowPacketsCommand.class);
        addCommand("showmovelife", 5, ShowMoveLifeCommand.class);
        addCommand("showsessions", 5, ShowSessionsCommand.class);
        addCommand("iplist", 5, IpListCommand.class);

        commandsNameDesc.add(levelCommandsCursor);
    }

    private void registerLv6Commands() {
        levelCommandsCursor = new Pair<>(new ArrayList<String>(), new ArrayList<String>());
        addCommand("togglemap", 6, ToggleMapCommand.class);
        addCommand("setgmlevel", 6, SetGmLevelCommand.class);
        addCommand("warpworld", 6, WarpWorldCommand.class);
        addCommand("saveall", 6, SaveAllCommand.class);
        addCommand("dcall", 6, DCAllCommand.class);
        addCommand("mapplayers", 6, MapPlayersCommand.class);
        addCommand("getacc", 6, GetAccCommand.class);
        addCommand("shutdown", 6, ShutdownCommand.class);
        addCommand("clearquestcache", 6, ClearQuestCacheCommand.class);
        addCommand("clearquest", 6, ClearQuestCommand.class);
        addCommand("supplyratecoupon", 6, SupplyRateCouponCommand.class);
        addCommand("spawnallpnpcs", 6, SpawnAllPNpcsCommand.class);
        addCommand("eraseallpnpcs", 6, EraseAllPNpcsCommand.class);
        addCommand("addchannel", 6, ServerAddChannelCommand.class);
        addCommand("addworld", 6, ServerAddWorldCommand.class);
        addCommand("removechannel", 6, ServerRemoveChannelCommand.class);
        addCommand("removeworld", 6, ServerRemoveWorldCommand.class);
        addCommand(new String[]{"setboss", "bossexped"}, 6, SetBossExpeditionCommand.class);

        commandsNameDesc.add(levelCommandsCursor);
    }

}
