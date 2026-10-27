package config;

import java.util.HashMap;
import java.util.Map;

public class ServerConfig {

    //Database Configuration
    public String DB_URL_FORMAT;
    public String DB_HOST;
    public String DB_USER;
    public String DB_PASS;
    public int INIT_CONNECTION_POOL_TIMEOUT;

    //Login Configuration
    public int WORLDS;
    public int WLDLIST_SIZE;
    public int CHANNEL_SIZE;
    public int CHANNEL_LOAD;
    public int CHANNEL_LOCKS;

    public long RESPAWN_INTERVAL;
    public long PURGING_INTERVAL;
    public long RANKING_INTERVAL;
    public long COUPON_INTERVAL;
    public long UPDATE_INTERVAL;

    public boolean ENABLE_PIC;
    public boolean ENABLE_PIN;

    public int BYPASS_PIC_EXPIRATION;
    public int BYPASS_PIN_EXPIRATION;

    public boolean AUTOMATIC_REGISTER;
    //Limits on automatic registration (LAN addresses are exempt from the per-IP ones). 0 = no account cap.
    //Owner 2026-10-26: any number of accounts per IP (friends share houses, alts); a short cooldown stops a burst.
    public int AUTO_REGISTER_MIN_PASSWORD_LENGTH = 6;
    public int AUTO_REGISTER_MAX_ACCOUNTS_PER_IP = 0;
    public int AUTO_REGISTER_IP_COOLDOWN_MINUTES = 1;
    //Daily Check-In window (needs migration 004 and a client with the window). Off until both are deployed.
    public boolean DAILY_CHECKIN_ENABLED = false;
    public int DAILY_CHECKIN_MIN_LEVEL = 10;
    //Coloring Prism (needs migration 010 and a slums.dll built with MAPLEROOT_COLOR_PRISM). Off until both are deployed.
    public boolean COLOR_PRISM_ENABLED = false;
    //Client version gate: a real player's login is refused when their slums.dll build (reported by CP 0x3738 before the
    //password packet; clients without it count as 0) is below MIN_CLIENT_BUILD. 0 = off. The release sets it to the
    //new client's build in the server apply step (tools/release/set-min-client-build), after the client is published.
    public int MIN_CLIENT_BUILD = 0;
    public String CLIENT_UPDATE_MESSAGE = "Your game is out of date: open the SlumsMS launcher to update.";
    //GM accounts (gm level >= 1) may log in with an older client, e.g. to check something on the old build.
    public boolean MIN_CLIENT_BUILD_GM_BYPASS = false;
    public boolean BCRYPT_MIGRATION;
    public boolean COLLECTIVE_CHARSLOT;
    public boolean DETERRED_MULTICLIENT;

    //Besides blocking logging in with several client sessions on the same machine, this also blocks suspicious login attempts for players that tries to login on an account using several diferent remote addresses.

    //Multiclient Coordinator Configuration
    public int MAX_ALLOWED_ACCOUNT_HWID;
    public int MAX_ACCOUNT_LOGIN_ATTEMPT;
    public int LOGIN_ATTEMPT_DURATION;
    //Per-IP gate on new login-server connections (LAN and loopback addresses all count as HOST). 0 = no limit.
    //Max login-server sessions open at once from one address (login + character select screens).
    public int LOGIN_MAX_SESSIONS_PER_IP = 8;
    //Max new login-server connections from one address within LOGIN_CONNECT_WINDOW_SECONDS.
    public int LOGIN_MAX_CONNECTS_PER_IP = 30;
    public int LOGIN_CONNECT_WINDOW_SECONDS = 60;

    //Ip Configuration
    public String HOST;
    public String LANHOST;
    public String LOCALHOST;
    public boolean GMSERVER;

    //Other configuration
    public boolean SHUTDOWNHOOK;

    //SoloMapling: spawn the bot world automatically on server startup (cold boot).
    public boolean SPAWN_BOTS_ON_STARTUP;

    //SoloMapling: the population director (bot-persistence ticket 08) - roster bots log in and out on a daily curve.
    public boolean BOT_DIRECTOR_ENABLED = true;
    // Ticket 13: real same-account characters as companions (foundation; opt in).
    public boolean OWNED_ALTS_ENABLED = false;
    //1.4.2 (perf): a player entering a map gets what is in view first; bots and drops outside the view follow
    //over the next ~1.5 s (server.maps.MapEntryPlacement). false = everything in one burst, as before.
    public boolean MAP_ENTRY_TRICKLE = true;
    //1.4.2 (ticket 57): the director's own peak. The cap below usually binds first.
    public int BOT_ROSTER_PEAK_ONLINE = 1200;
    public String BOT_ROSTER_CURVE = "0:0.45,3:0.25,6:0.15,9:0.25,12:0.45,15:0.6,18:0.85,20:1.0,22:0.8";
    public double BOT_ROSTER_WEEKEND_LIFT = 1.1;
    public String BOT_ROSTER_TIMEZONE = "";
    public int BOT_DIRECTOR_TICK_SECONDS = 30;
    public int BOT_DIRECTOR_MAX_LOGINS_PER_TICK = 25;
    public int BOT_DIRECTOR_MAX_LOGOUTS_PER_TICK = 25;
    public int BOT_ROSTER_SEED_MIN = 100;
    //1.4.2 (ticket 57): every bot online at once, environment and director together, at the curve's peak. The
    //director's peak is min(BOT_ROSTER_PEAK_ONLINE, this - environment bots online); 0 = no cap.
    public int BOT_POPULATION_PEAK = 1600;   //owner 2026-10-22 (rc5): 1,200 roster at peak PLUS the ~360 environment bots, as on 1.4.1; 1,200 total thinned channel 1
    //1.4.2 (ticket 57): the share of the boot environment's town dressing (Henesys fillers, FM entrance, FM shop
    //bots...) that spawns; set pieces (tutorial, casino tables, drop game, OPQ lobby, gacha) always spawn. 1.0 = all.
    public double BOT_ENVIRONMENT_SCALE = 0.5;
    //1.5.2 hotfix: the same share for the FM entrance's FM bots and merchants (still split over the channels like
    //the rest of the dressing). 1.0 = all 90, about 40 on channel 1; BOT_ENVIRONMENT_SCALE's 0.5 left about 20.
    public double BOT_FM_ENVIRONMENT_SCALE = 1.0;
    //1.4.2 (ticket 57): how bots split over the channels, ch1 first. Any count of weights; channels past the list
    //get the last weight; they are normalised. Blank = every channel alike.
    //owner 2026-10-21: 5 channels, weights 100/85/50/30/20. Live config.yaml for 1.5.0: channels 5, CHANNEL_LOAD 125
    //(owner 2026-10-22), so the peak bars sit at about 85/70/40/25/15 %.
    public String BOT_CHANNEL_WEIGHTS = "100,55,35,20,15"; //owner 2026-10-22 (rc5): channel 1 clearly the busiest, ~45%
    //1.5.0 (3k bots, owner 2026-10-23): the most bots the director, grind, town, FM and travel picks put on one map
    //instance (one map on one channel). A full map makes a bot pick another map or another channel's instance;
    //bots already there are never moved. Staff set pieces (merchants, dealers, casino, the boot town dressing) and
    //bots following a player or a story scene are exempt but counted. 0 = no cap. See soloMapling.ArtificialPlayer.BotMapCap.
    public int BOT_MAP_CAP = 50;
    //Per-map caps over BOT_MAP_CAP, "mapId:cap,mapId:cap" (e.g. "910000000:60,100000000:70"); 0 = no cap there.
    public String BOT_MAP_CAP_OVERRIDES = "";
    //1.6.0 (owner 2026-10-27): while an invasion runs, its map takes up to this many bots (the crowd plus the town's
    //own) instead of BOT_MAP_CAP; back to the usual cap when it ends. A real client held 620 bots on one map and only
    //lagged at ~1,000. Never lowers a map's own cap.
    public int WORLD_EVENT_MAP_MAX = 200;
    //Auto-QA (docs/ops/qa-suite.md "Auto-QA"; test server only): health sweeps every AUTOQA_SWEEP_MINUTES and the
    //synthetic-player check runner. Off unless this is true or the JVM has -Dslums.autoqa=true. Results go to data/autoqa/.
    public boolean AUTOQA_ENABLED = false;
    public int AUTOQA_SWEEP_MINUTES = 5;
    public int AUTOQA_SWEEP_MAX_BOTS = 3000;    //bots looked at per sweep (an even sample past this)
    public int AUTOQA_STUCK_MINUTES = 3;        //same spot this long while moving = stuck
    public int AUTOQA_CLUMP_RADIUS = 30;        //px
    public int AUTOQA_CLUMP_MIN_BOTS = 5;       //driven bots within the radius that make a clump
    public int AUTOQA_NIGHTLY_UTC_HOUR = 10;    //the nightly '!autoqa run all' (10 UTC = 3 AM PDT); -1 = no nightly run
    public int AUTOQA_JUDGE_MAX_CALLS = 40;     //LLM judge calls per run at most
    //SoloMapling: shrink the seed waves (training cohorts + town presence) for a pilot on a small host; 1.0 = full.
    public double BOT_POPULATION_SCALE = 1.0;
    //SoloMapling: roster lifecycle (bot-persistence ticket 10) - new players, quitting, coming back. Rates are per day.
    public double BOT_ROSTER_SIZE_MULTIPLE = 3.0;
    public int BOT_ROSTER_NEW_PER_DAY = 40;
    public double BOT_ROSTER_VETERAN_FILL_BELOW = 0.5;
    public int BOT_ROSTER_VETERANS_PER_DAY = 300;
    public double BOT_ROSTER_QUIT_PER_DAY = 0.002;
    public double BOT_ROSTER_RETURN_PER_DAY = 0.01;

    //SoloMapling: player-directed bot chat through any OpenAI-compatible endpoint (bot-persistence ticket 12).
    //Blank URL = scripted dialogue only. Each EXTRA_BODY value is a JSON fragment merged into the request body.
    public String BOT_LLM_BASE_URL = "";
    public String BOT_LLM_API_KEY = "";
    public String BOT_LLM_MODEL = "";
    public int BOT_LLM_MAX_TOKENS = 40;
    public double BOT_LLM_TEMPERATURE = 1.0;
    public int BOT_LLM_TIMEOUT_MS = 7000;
    public int BOT_LLM_MAX_CONCURRENT = 2;
    public int BOT_LLM_PLAYER_MIN_INTERVAL_MS = 1500;
    public Map<String, String> BOT_LLM_EXTRA_BODY = new HashMap<>();
    public Map<String, String> BOT_LLM_EXTRA_HEADERS = new HashMap<>();
    public String BOT_LLM_NOTES_MODEL = "";
    //Ticket 59 (router): an optional OpenAI-compatible cloud provider for OVERFLOW only - replies to real players when
    //the local model's slots are all busy or it is slow. Blank URL = no cloud. The key can also come from env
    //SOLOMAPLING_LLM_CLOUD_API_KEY. Spend is estimated from tokens and capped per server-local day.
    public String BOT_LLM_CLOUD_BASE_URL = "";
    public String BOT_LLM_CLOUD_API_KEY = "";
    public String BOT_LLM_CLOUD_MODEL = "";
    public int BOT_LLM_CLOUD_TIMEOUT_MS = 6000;
    public double BOT_LLM_CLOUD_DAILY_BUDGET_USD = 1.0;
    //DeepSeek's peak prices for deepseek-flash (2026-10-22); off-peak is half, so this overestimates
    public double BOT_LLM_CLOUD_PRICE_IN_PER_MTOK = 0.30;
    public double BOT_LLM_CLOUD_PRICE_OUT_PER_MTOK = 1.20;
    //Extra request fields for the cloud as raw JSON, e.g. reasoning_effort: '"none"': thinking models spend max_tokens thinking and
    //answer nothing without it
    public Map<String, String> BOT_LLM_CLOUD_EXTRA_BODY = new HashMap<>();
    //Extra HTTP headers for the cloud, e.g. OpenRouter's HTTP-Referer and X-Title.
    public Map<String, String> BOT_LLM_CLOUD_EXTRA_HEADERS = new HashMap<>();
    //Where the cloud sits: overflow (players' replies when local is full or slow), players (players' replies go to
    //the cloud first) or all (every chat call goes to the cloud first; threads, ambient and notes stop at 80% of the
    //daily budget). Local is the fallback in every mode, and the daily budget is a hard cap.
    public String BOT_LLM_CLOUD_MODE = "overflow";
    //Local model latency (moving average, ms) above which a real player's reply overflows to the cloud.
    public int BOT_LLM_LOCAL_SLOW_MS = 2500;
    //1.5.0 rc3 (bots fight like people): only maps a real player can see run any of this (BotCombatTuning).
    //Master switch for the human combat layer: class-correct skill choice, facing first, touch avoidance,
    //platform pacing, per-bot loot/potion habits. false = the 1.5.0 rc2 behaviour.
    public boolean BOT_COMBAT_HUMAN = true;
    //Mobs in reach before a bot throws its AoE (owner: 3+); bosses and lone mobs always get the single-target skill.
    public int BOT_COMBAT_AOE_MIN_MOBS = 3;
    //Scales every bot's own chance of a suboptimal cast and a late rebuff (0 = textbook rotation).
    public double BOT_COMBAT_SLOPPINESS = 1.0;
    //Rare deaths on maps above a bot's level: it lies down, respawns in town and walks back.
    public boolean BOT_COMBAT_DEATHS = true;
    //Neutral bots stay off a real player's platform and move on from a crowded map (rivals still KS).
    public boolean BOT_KS_MANNERS = true;
    //Spread: claims per channel, squatters get a band, personal space between bots, one bot per rope.
    public boolean BOT_SPREAD = true;
    //SoloMapling: bot menus in the client's NPC window (clickable) instead of numbered chat hints (ticket 23).
    public boolean BOT_MENU_WINDOW = true;

    //SoloMapling: who a chat line is aimed at, when it carries no usable name (bot-persistence ticket 19).
    public String BOT_DECIDE_URL = "";
    public int BOT_DECIDE_TIMEOUT_MS = 600;
    public double BOT_DECIDE_ASK = 0.55;

    //Server Flags
    public boolean USE_CUSTOM_KEYSET;
    public boolean USE_DEBUG;
    public boolean USE_DEBUG_SHOW_INFO_EQPEXP;
    public boolean USE_DEBUG_SHOW_RCVD_PACKET;
    public boolean USE_DEBUG_SHOW_RCVD_MVLIFE;
    public boolean USE_DEBUG_SHOW_PACKET;
    public boolean USE_SUPPLY_RATE_COUPONS;
    public boolean USE_IP_VALIDATION;
    public boolean USE_CHARACTER_ACCOUNT_CHECK;

    public boolean USE_MAXRANGE;
    public boolean USE_MAXRANGE_ECHO_OF_HERO;
    public boolean USE_MTS;
    public boolean USE_CPQ;
    public boolean USE_AUTOHIDE_GM;
    public boolean USE_BUYBACK_SYSTEM;
    public boolean USE_FIXED_RATIO_HPMP_UPDATE;
    public boolean USE_FAMILY_SYSTEM;
    //Godly drops (owner 2026-10-25, 1.5.2, server.GodlyDrops): this % of monster equip drops gets GODLY_DROP_STATS of
    //its existing stats raised by GODLY_DROP_MIN_BONUS..GODLY_DROP_MAX_BONUS. 0 = off.
    public double GODLY_DROP_PERCENT = 1.5;
    public int GODLY_DROP_STATS = 3;
    public int GODLY_DROP_MIN_BONUS = 1;
    public int GODLY_DROP_MAX_BONUS = 5;
    public boolean USE_DUEY = true;   //owner 2026-10-25 (1.5.2): Duey on, as stock v83 (bots never send or receive)
    public boolean USE_RANDOMIZE_HPMP_GAIN;
    public boolean USE_STORAGE_ITEM_SORT;
    public boolean USE_ITEM_SORT;
    public boolean USE_ITEM_SORT_BY_NAME;
    public boolean USE_PARTY_FOR_STARTERS;
    public boolean USE_AUTOASSIGN_STARTERS_AP;
    public boolean USE_AUTOASSIGN_SECONDARY_CAP;
    public boolean USE_STARTING_AP_4;
    public boolean USE_AUTOBAN;
    public boolean USE_AUTOBAN_LOG;
    public boolean USE_AUTOSAVE;
    public boolean USE_SERVER_AUTOASSIGNER;
    public boolean USE_REFRESH_RANK_MOVE;
    public boolean USE_ENFORCE_ADMIN_ACCOUNT;
    public boolean USE_ENFORCE_NOVICE_EXPRATE;
    public boolean USE_ENFORCE_HPMP_SWAP;
    public boolean USE_ENFORCE_MOB_LEVEL_RANGE;
    public boolean USE_ENFORCE_JOB_LEVEL_RANGE;
    public boolean USE_ENFORCE_JOB_SP_RANGE;
    public boolean USE_ENFORCE_ITEM_SUGGESTION;
    public boolean USE_ENFORCE_UNMERCHABLE_CASH;
    public boolean USE_ENFORCE_UNMERCHABLE_PET;
    public boolean USE_ENFORCE_MERCHANT_SAVE;
    public boolean USE_ENFORCE_MDOOR_POSITION;
    public boolean USE_SPAWN_CLEAN_MDOOR;
    public boolean USE_SPAWN_LOOT_ON_ANIMATION;
    public boolean USE_SPAWN_RELEVANT_LOOT;
    public boolean USE_ERASE_PERMIT_ON_OPENSHOP;
    public boolean USE_ERASE_UNTRADEABLE_DROP;
    public boolean USE_ERASE_PET_ON_EXPIRATION;
    public boolean USE_BUFF_MOST_SIGNIFICANT;
    public boolean USE_BUFF_EVERLASTING;
    public boolean USE_MULTIPLE_SAME_EQUIP_DROP;
    public boolean USE_BANISHABLE_TOWN_SCROLL;
    public boolean USE_ENABLE_FULL_RESPAWN;
    public boolean USE_ENABLE_CHAT_LOG;
    public boolean USE_REBIRTH_SYSTEM;
    public boolean USE_MAP_OWNERSHIP_SYSTEM;
    public boolean USE_FISHING_SYSTEM;
    public boolean USE_NPCS_SCRIPTABLE;

    //Events/PQs Configuration
    public boolean USE_OLD_GMS_STYLED_PQ_NPCS;
    public boolean USE_ENABLE_SOLO_EXPEDITIONS;
    public boolean USE_ENABLE_DAILY_EXPEDITIONS;
    public boolean USE_ENABLE_RECALL_EVENT;

    //Announcement Configuration
    public boolean USE_ANNOUNCE_SHOPITEMSOLD;
    public boolean USE_ANNOUNCE_CHANGEJOB;
    public boolean USE_ANNOUNCE_NX_COUPON_LOOT;

    //Cash Shop Configuration
    public boolean USE_JOINT_CASHSHOP_INVENTORY;
    public boolean USE_CLEAR_OUTDATED_COUPONS;
    public boolean ALLOW_CASHSHOP_NAME_CHANGE;
    public boolean ALLOW_CASHSHOP_WORLD_TRANSFER;//Allows players to buy world transfers in the cash shop.

    //Maker Configuration
    public boolean USE_MAKER_PERMISSIVE_ATKUP;
    public boolean USE_MAKER_FEE_HEURISTICS;

    //Custom Configuration
    public boolean USE_ENABLE_CUSTOM_NPC_SCRIPT;
    public boolean USE_STARTER_MERGE;

    //Commands Configuration
    public boolean BLOCK_GENERATE_CASH_ITEM;
    public boolean USE_WHOLE_SERVER_RANKING;

    public double EQUIP_EXP_RATE;
    public double PQ_BONUS_EXP_RATE;

    public byte EXP_SPLIT_LEVEL_INTERVAL;
    public byte EXP_SPLIT_LEECH_INTERVAL;
    public float EXP_SPLIT_MVP_MOD;
    public float EXP_SPLIT_COMMON_MOD;
    public float PARTY_BONUS_EXP_RATE;

    //Miscellaneous Configuration
    public String TIMEZONE;
    public String CHARSET;
    public boolean USE_DISPLAY_NUMBERS_WITH_COMMA;
    public boolean USE_UNITPRICE_WITH_COMMA;
    public byte MAX_MONITORED_BUFFSTATS;
    public int MAX_AP;
    public int MAX_EVENT_LEVELS;
    public long BLOCK_NPC_RACE_CONDT;
    public int TOT_MOB_QUEST_REQUIREMENT;
    public int MOB_REACTOR_REFRESH_TIME;
    public int PARTY_SEARCH_REENTRY_LIMIT;
    public long NAME_CHANGE_COOLDOWN;
    public long WORLD_TRANSFER_COOLDOWN = NAME_CHANGE_COOLDOWN;//Cooldown for world tranfers, default is same as name change (30 days).
    public boolean INSTANT_NAME_CHANGE;
    public int REBIRTH_NPC_ID;

    //Dangling Items/Locks Configuration
    public int ITEM_EXPIRE_TIME;
    public int KITE_EXPIRE_TIME;
    public int ITEM_MONITOR_TIME;
    public int LOCK_MONITOR_TIME;

    //Map Monitor Configuration
    public int ITEM_EXPIRE_CHECK;
    public int ITEM_LIMIT_ON_MAP;
    public int MAP_VISITED_SIZE;
    public int MAP_DAMAGE_OVERTIME_INTERVAL;
    public int MAP_DAMAGE_OVERTIME_COUNT;

    //Channel Mob Disease Monitor Configuration
    public int MOB_STATUS_MONITOR_PROC;
    public int MOB_STATUS_MONITOR_LIFE;
    public int MOB_STATUS_AGGRO_PERSISTENCE;
    public int MOB_STATUS_AGGRO_INTERVAL;
    public boolean USE_AUTOAGGRO_NEARBY;

    //Some Gameplay Enhancing Configurations
    //Scroll Configuration
    public boolean USE_PERFECT_GM_SCROLL;
    public boolean USE_PERFECT_SCROLLING;
    public boolean USE_ENHANCED_CHSCROLL;
    public boolean USE_ENHANCED_CRAFTING;
    public boolean USE_ENHANCED_CLEAN_SLATE;
    public int SCROLL_CHANCE_ROLLS;
    public int CHSCROLL_STAT_RATE;
    public int CHSCROLL_STAT_RANGE;

    //Beginner Skills Configuration
    public boolean USE_ULTRA_NIMBLE_FEET;
    public boolean USE_ULTRA_RECOVERY;
    public boolean USE_ULTRA_THREE_SNAILS;

    //Other Skills Configuration
    public boolean USE_FULL_ARAN_SKILLSET;
    public boolean USE_FAST_REUSE_HERO_WILL;
    public boolean USE_ANTI_IMMUNITY_CRASH;
    public boolean USE_UNDISPEL_HOLY_SHIELD;
    public boolean USE_FULL_HOLY_SYMBOL;

    //Character Configuration
    public boolean USE_ADD_SLOTS_BY_LEVEL;
    public boolean USE_ADD_RATES_BY_LEVEL;
    public boolean USE_STACK_COUPON_RATES;
    public boolean USE_PERFECT_PITCH;
    public int REBIRTH_FIRST_RATE;
    public int REBIRTH_SECOND_RATE;
    public int REBIRTH_THIRD_RATE;

    public int REBIRTH_RESET_LEVEL;

    public int REBIRTH_FINAL_RATE;
    public int AUTOLOOT_ITEM_ID;
    public int JUMPQUEST_CUSTOM_EXP_MESOS_REWARD;
    public int JUMPQUEST_BASE_EXP_REWARD;
    public int JUMPQUEST_BASE_MESOS_REWARD;

    //Quest Configuration
    public boolean USE_QUEST_RATE;

    //Quest Points Configuration
    public int QUEST_POINT_REPEATABLE_INTERVAL;
    public int QUEST_POINT_REQUIREMENT;
    public int QUEST_POINT_PER_QUEST_COMPLETE;
    public int QUEST_POINT_PER_EVENT_CLEAR;

    //Guild Configuration
    public int CREATE_GUILD_MIN_PARTNERS;
    public int CREATE_GUILD_COST;
    public int CHANGE_EMBLEM_COST;
    public int EXPAND_GUILD_BASE_COST;
    public int EXPAND_GUILD_TIER_COST;
    public int EXPAND_GUILD_MAX_COST;

    //Family Configuration
    public int FAMILY_REP_PER_KILL;
    public int FAMILY_REP_PER_BOSS_KILL;
    public int FAMILY_REP_PER_LEVELUP;
    public int FAMILY_MAX_GENERATIONS;

    //Equipment Configuration
    public boolean USE_EQUIPMNT_LVLUP_SLOTS;
    public boolean USE_EQUIPMNT_LVLUP_POWER;
    public boolean USE_EQUIPMNT_LVLUP_CASH;
    public boolean USE_SPIKES_AVOID_BANISH;
    public int MAX_EQUIPMNT_LVLUP_STAT_UP;
    public int MAX_EQUIPMNT_STAT;
    public int USE_EQUIPMNT_LVLUP;

    //Map-Chair Configuration
    public boolean USE_CHAIR_EXTRAHEAL;
    public byte CHAIR_EXTRA_HEAL_MULTIPLIER;
    public int CHAIR_EXTRA_HEAL_MAX_DELAY;

    //Player NPC Configuration
    public int PLAYERNPC_INITIAL_X;
    public int PLAYERNPC_INITIAL_Y;
    public int PLAYERNPC_AREA_X;
    public int PLAYERNPC_AREA_Y;
    public int PLAYERNPC_AREA_STEPS;
    public boolean PLAYERNPC_ORGANIZE_AREA;
    public boolean PLAYERNPC_AUTODEPLOY;

    //Pet Auto-Pot Configuration
    public boolean USE_COMPULSORY_AUTOPOT;
    public boolean USE_EQUIPS_ON_AUTOPOT;
    public double PET_AUTOHP_RATIO;
    public double PET_AUTOMP_RATIO;

    //Pet & Mount Configuration
    public byte PET_EXHAUST_COUNT;
    public byte MOUNT_EXHAUST_COUNT;

    //Pet Hunger Configuration
    public boolean PETS_NEVER_HUNGRY;
    public boolean GM_PETS_NEVER_HUNGRY;

    //Event Configuration
    public int EVENT_MAX_GUILD_QUEUE;
    public long EVENT_LOBBY_DELAY;

    //Dojo Configuration
    public boolean USE_FAST_DOJO_UPGRADE;
    public boolean USE_DEADLY_DOJO;
    public int DOJO_ENERGY_ATK;
    public int DOJO_ENERGY_DMG;

    //Wedding Configuration
    public int WEDDING_RESERVATION_DELAY;
    public int WEDDING_RESERVATION_TIMEOUT;
    public int WEDDING_RESERVATION_INTERVAL;
    public int WEDDING_BLESS_EXP;
    public int WEDDING_GIFT_LIMIT;
    public boolean WEDDING_BLESSER_SHOWFX;

    //Buyback Configuration
    public boolean USE_BUYBACK_WITH_MESOS;
    public float BUYBACK_FEE;
    public float BUYBACK_LEVEL_STACK_FEE;
    public int BUYBACK_MESO_MULTIPLIER;
    public int BUYBACK_RETURN_MINUTES;
    public int BUYBACK_COOLDOWN_MINUTES;

    // Login timeout by shavit
    public long TIMEOUT_DURATION;

    //Event End Timestamp
    public long EVENT_END_TIMESTAMP;

    //GM Security Configuration
    //owner 2026-10-25 (1.5.2): back to Cosmic's 4 (MapleRoot shipped 1: any GM could trade, drop, use storage and Duey).
    //GmGiving (ticket 43) still blocks GM-to-player trades and drops unless -Dslumsms.gm.allowGiving=true.
    public int MINIMUM_GM_LEVEL_TO_TRADE = 4;
    public int MINIMUM_GM_LEVEL_TO_USE_STORAGE = 4;
    public int MINIMUM_GM_LEVEL_TO_USE_DUEY = 4;
    public int MINIMUM_GM_LEVEL_TO_DROP = 4;

    //Custom NPC overrides. List of NPC IDs.
    public Map<String, String> NPCS_SCRIPTABLE = new HashMap<>();

    // amount of characters that can receive expedition rewards on the same HWID
    public int EXPEDITION_HWID_LIMIT;

    // maximum number of transactions that can be pulled for buyback command
    public int MAXIMUM_TRANSACTIONS_FOR_BUYBACK;

    // determine if the thread tracker will be used to improve visibly for deadlocks
    public boolean USE_THREAD_TRACKER;

    // Starforce
    public int STARFORCE_MAX_MESO_COST;
    public int STARFORCE_MAX_SPELL_TRACE_COST;
    public double STARFORCE_BOOST_PERCENTAGE;
    public double STARFORCE_BOOST_PERCENTAGE2;

    // BACKPACK
    public int BACKPACK_MAX_ITEMS_PER_PAGE;
    public int BACKPACK_MAX_STORAGE_PER_ITEM;
    public int BACKPACK_MINIMUM_SEARCH_LENGTH;
    public int BACKPACK_MAX_UNIQUE_ITEMS;
}
