package soloMapling.mechanics;

import client.Character;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The live {@link StageView} (ticket 130 phase 1): a snapshot of one run's event instance taken at the start of a
 * tick. The server is the truth: the member's question comes from the event's grid (what our Cloto script stored with
 * {@code eim.gridInsert}), the clear from the event property the script sets, items from the characters' own
 * inventories, passes on the floor from the stage map. Read-only.
 */
final class LiveStageView implements StageView {

    private final RunState run;
    private final EventInstanceManager eim;
    private final MapleMap map;
    private final Map<Integer, Character> chars = new LinkedHashMap<>();
    private final List<RunMember> present = new ArrayList<>();

    private final long now;

    LiveStageView(RunState run, EventInstanceManager eim) {
        this(run, eim, System.currentTimeMillis());
    }

    LiveStageView(RunState run, EventInstanceManager eim, long now) {
        this.run = run;
        this.eim = eim;
        this.now = now;
        Stage stage = run.stageData();
        this.map = eim.isEventDisposed() ? null : eim.getMapInstance(stage.mapId());
        if (!eim.isEventDisposed()) {
            for (Character c : eim.getPlayers()) {
                if (c != null) {
                    chars.put(c.getId(), c);
                }
            }
        }
        for (RunMember m : run.members()) {
            if (chars.containsKey(m.characterId())) {
                present.add(m);
            }
        }
    }

    @Override
    public boolean alive(int characterId) {
        Character c = character(characterId);
        return c == null || c.getHp() > 0;
    }

    Character character(int id) {
        return chars.get(id);
    }

    MapleMap map() {
        return map;
    }

    @Override
    public Stage stage() {
        return run.stageData();
    }

    @Override
    public List<RunMember> members() {
        return List.copyOf(present);
    }

    @Override
    public String roleOf(int characterId) {
        return run.roleOf(characterId);
    }

    @Override
    public OptionalInt answerFor(int characterId) {
        if (stage().answerProperty() != null) {
            return CombinationSolver.answer(this); // one answer for the stage, kept in the event property
        }
        int q = memberStatus(characterId);
        return q > 0 ? stage().answer(q) : OptionalInt.empty();
    }

    @Override
    public int mapOf(int characterId) {
        Character c = chars.get(characterId);
        return c == null ? -1 : c.getMapId();
    }

    /**
     * The boss parts standing on the stage map (ticket 130 phase 5). A part counts as fought by a human when a real
     * player of the run is alive on the stage: this tree keeps no per-part damage ledger a rule could read, so "a human
     * is in the fight at all" stands in for BotStory's engaged test (see the ticket's assumptions).
     */
    @Override
    public List<BossTargetRules.Part> bossParts() {
        if (map == null) {
            return List.of();
        }
        List<Integer> listed = stage().mobs();
        boolean humanIn = present.stream().anyMatch(m -> !m.bot() && alive(m.characterId()));
        List<BossTargetRules.Part> out = new ArrayList<>();
        for (Monster m : map.getAllMonsters()) {
            if (m == null || !m.isAlive()) {
                continue;
            }
            BossTargetRules.Role role = partRole(m.getId(), listed);
            if (role == null) {
                continue;
            }
            out.add(new BossTargetRules.Part(m.getId(), role, m.isFake(), role == BossTargetRules.Role.ADD || humanIn,
                    m.isBuffed(client.status.MonsterStatus.WEAPON_IMMUNITY),
                    m.isBuffed(client.status.MonsterStatus.MAGIC_IMMUNITY), 0));
        }
        return out;
    }

    @Override
    public BossTargetRules.Member fighterOf(int characterId) {
        Character c = chars.get(characterId);
        soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile profile =
                c == null ? null : soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.primaryAttack(c);
        if (profile == null) {
            return new BossTargetRules.Member(false, true);
        }
        return new BossTargetRules.Member(profile.route == soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.Route.MAGIC,
                profile.route == soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.Route.CLOSE);
    }

    /** The event's own instance of another map (a boss run's next room), or null when the instance has none. */
    MapleMap mapFor(int mapId) {
        return eim.isEventDisposed() ? null : eim.getMapInstance(mapId);
    }

    /**
     * The fight's role of a live monster: a part that shields the body, the body itself, an add the stage lists, or
     * null (not this fight). Zakum's arms are 8800003-8810010 and its body 8800000-8800002; Horntail's parts are
     * 8810002-8810009 under its body 8810018, its two pre-heads 8810000/8810001 are the single target of the first two
     * rooms, and its corpse parts 8810010-8810017 and its summon animation 8810026 are never a target.
     */
    static BossTargetRules.Role partRole(int mobId, List<Integer> listed) {
        if (constants.id.MobId.isDeadHorntailPart(mobId) || mobId == constants.id.MobId.SUMMON_HORNTAIL) {
            return null;
        }
        if (constants.id.MobId.isZakumArm(mobId)
                || (mobId >= constants.id.MobId.HORNTAIL_HEAD_A && mobId <= constants.id.MobId.HORNTAIL_TAIL)) {
            return BossTargetRules.Role.ARM;
        }
        if (mobId == constants.id.MobId.ZAKUM_1 || mobId == constants.id.MobId.ZAKUM_2
                || mobId == constants.id.MobId.ZAKUM_3
                || mobId == constants.id.MobId.HORNTAIL_PREHEAD_LEFT
                || mobId == constants.id.MobId.HORNTAIL_PREHEAD_RIGHT
                || mobId == constants.id.MobId.HORNTAIL) {
            return BossTargetRules.Role.BODY;
        }
        return listed.contains(mobId) ? BossTargetRules.Role.ADD : null;
    }

    @Override
    public int liveMobs() {
        if (map == null) {
            return 0;
        }
        java.util.List<Integer> wanted = stage().mobs();
        int n = 0;
        for (server.life.Monster m : map.getAllMonsters()) {
            if (m != null && m.isAlive() && (wanted.isEmpty() || wanted.contains(m.getId()))) {
                n++;
            }
        }
        return n;
    }

    @Override
    public long nowMs() {
        return now;
    }

    @Override
    public Map<String, Object> stageMemory() {
        return run.stageMemory();
    }

    @Override
    public int memberStatus(int characterId) {
        Character c = chars.get(characterId);
        return c == null || eim.isEventDisposed() ? -1 : eim.gridCheck(c);
    }

    @Override
    public int itemCount(int characterId, int itemId) {
        Character c = chars.get(characterId);
        return c == null ? 0 : c.getItemQuantity(itemId, false);
    }

    @Override
    public Optional<String> eventProperty(String key) {
        return eim.isEventDisposed() ? Optional.empty() : Optional.ofNullable(eim.getProperty(key));
    }

    @Override
    public int[] positionOf(int characterId) {
        Character c = chars.get(characterId);
        Point p = c == null ? null : c.getPosition();
        return p == null ? new int[]{0, 0} : new int[]{p.x, p.y};
    }

    @Override
    public int floorItems(int itemId, int forCharacterId) {
        return floorDrops(this, map, itemId, forCharacterId).size();
    }

    @Override
    public List<Integer> wrongTriesLeft(int characterId) {
        return run.wrongTriesLeft(characterId);
    }

    @Override
    public Optional<int[]> npcPosition(int npcId) {
        NPC npc = map == null ? null : map.getNPCById(npcId);
        Point p = npc == null ? null : npc.getPosition();
        return p == null ? Optional.empty() : Optional.of(new int[]{p.x, p.y});
    }

    @Override
    public Optional<FloorDrop> dropBy(int dropperId, int itemId) {
        MapItem mi = ownDrop(map, itemId, dropperId);
        return mi == null ? Optional.empty() : Optional.of(new FloorDrop(mi.getPosition().x, mi.getPosition().y,
                mi.getOwnerId(), mi.getDropTime()));
    }

    /** The newest drop of {@code itemId} that {@code dropperId} dropped on this map and nobody took yet, or null. */
    static MapItem ownDrop(MapleMap map, int itemId, int dropperId) {
        MapItem best = null;
        if (map == null) {
            return null;
        }
        for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                List.of(MapObjectType.ITEM))) {
            if (o instanceof MapItem mi && !mi.isPickedUp() && mi.getMeso() == 0 && mi.getItemId() == itemId
                    && dropperOf(mi) == dropperId && (best == null || mi.getDropTime() > best.getDropTime())) {
                best = mi;
            }
        }
        return best;
    }

    /** The character id that dropped this item (0 for a monster's or a reactor's drop). */
    static int dropperOf(MapItem mi) {
        return mi.getDropper() instanceof Character c ? c.getId() : 0;
    }

    /**
     * Drops of {@code itemId} on the map that this member may take: its own, a monster's drop by the game's party
     * loot rule (a pass a fellow member's kill left), or a hand-over made for it.
     */
    static List<MapItem> floorDrops(StageView view, MapleMap map, int itemId, int forCharacterId) {
        List<MapItem> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY,
                List.of(MapObjectType.ITEM))) {
            if (o instanceof MapItem mi && !mi.isPickedUp() && mi.getMeso() == 0 && mi.getItemId() == itemId
                    && mayTake(view, forCharacterId, mi)) {
                out.add(mi);
            }
        }
        return out;
    }

    /**
     * Whether {@code memberId} may take {@code mi}. A monster's drop follows the game's loot rule
     * ({@link QuestionCouponsSolver#mayTakeMobCoupon}: its own kill, a party member's, or anyone's once the ownership
     * window ran out) — a KPQ pass is such a drop, and stage 5's leader needs the ones his party's kills left. A
     * character's drop is a hand-over and only the member it was made for takes it ({@link PassHandover#mayTake}).
     */
    static boolean mayTake(StageView view, int memberId, MapItem mi) {
        if (mi.getDropper() instanceof Monster) {
            return mi.getDropType() == 2 || QuestionCouponsSolver.mayTakeMobCoupon(view, memberId, mi.getOwnerId(),
                    mi.hasExpiredOwnershipTime());
        }
        return PassHandover.mayTake(memberId, mi.getOwnerId(), dropperOf(mi), mi.getDropType());
    }
}
