package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotEpisodes;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialWorld;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * The real game behind {@link SocialWorld}: characters' positions from the live map (memory only), the bot-talk
 * availability checks ({@link BotTalk#free}, {@code BotPopulationDirector.mayLogOut}), map chat through
 * {@code BotSpeak}, emotes through {@code BotEmote}, walks through {@code GCMovement.move} (the destination checked
 * against where others stand by {@code PersonalSpace.freeSpotNear} where that applies) and the bot-to-bot memory through
 * {@code BotMemory} / {@code BotEpisodes}. Lives in the talk package because the availability checks do.
 */
public final class GameSocialWorld implements SocialWorld {

    private static BotSM sm(int id) {
        return id >= BotStore.BOT_ID_BASE ? CharacterStorage.getBotById(id) : null;
    }

    private static Character chr(int id) {
        BotSM s = sm(id);
        return s == null ? null : s.getChr();
    }

    private static Body bodyOf(Character c) {
        MapleMap m = c.getMap();
        Point p = c.getPosition();
        if (m == null || p == null) {
            return null;
        }
        return new Body(c.getId(), c.getName(), BotTalk.key(m.getChannelId(), m.getId()), m.getId(), p.x, p.y,
                c.getLevel(), c.getId() < BotStore.BOT_ID_BASE);
    }

    @Override
    public Body body(int charId) {
        try {
            Character c = chr(charId);
            return c == null ? null : bodyOf(c);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Override
    public List<Body> nearby(int charId, int radiusX, int radiusY) {
        Character me = chr(charId);
        Body self = me == null ? null : bodyOf(me);
        return self == null ? List.of() : box(me.getMap(), self.x(), self.y(), radiusX, radiusY, charId);
    }

    @Override
    public List<Body> around(int viewerCharId, int x, int y, int radiusX, int radiusY) {
        Character me = chr(viewerCharId);
        return me == null || me.getMap() == null ? List.of() : box(me.getMap(), x, y, radiusX, radiusY, viewerCharId);
    }

    private static List<Body> box(MapleMap map, int x, int y, int rx, int ry, int exceptId) {
        List<Body> out = new ArrayList<>(4);
        try {
            for (Character o : map.getAllPlayers()) {
                if (o == null || o.getId() == exceptId || o.getId() < BotStore.BOT_ID_BASE) {
                    continue; // bots only: a real player is never part of a bot's social picture here
                }
                Point p = o.getPosition();
                if (p != null && Math.abs(p.x - x) <= rx && Math.abs(p.y - y) <= ry) {
                    Body b = bodyOf(o);
                    if (b != null) {
                        out.add(b);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return out;
        }
        return out;
    }

    @Override
    public boolean free(int charId) {
        try {
            BotSM s = sm(charId);
            Character c = s == null ? null : s.getChr();
            return c != null && c.getMap() != null && BotTalk.free(c, false) && BotPopulationDirector.mayLogOut(s);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean idle(int charId) {
        try {
            Character c = chr(charId);
            return free(charId) && BotTalk.free(c, true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public boolean say(int charId, String text) {
        try {
            Character c = chr(charId);
            if (c == null || c.getMap() == null || text == null || text.isBlank()) {
                return false;
            }
            SayDo.withSource("social-act", () -> BotSpeak(c, text));
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public void emote(int charId, int emote) {
        try {
            Character c = chr(charId);
            if (c != null && c.getMap() != null) {
                BotEmote(c, emote);
            }
        } catch (RuntimeException | LinkageError e) {
            // an emote is decoration
        }
    }

    @Override
    public void walkTo(int charId, int x, int y) {
        try {
            Character c = chr(charId);
            if (c == null || c.getMap() == null) {
                return;
            }
            Point want = new Point(x, y);
            try {
                Point free = soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.freeSpotNear(c.getMap(), want, charId);
                if (free != null) {
                    want = free;
                }
            } catch (RuntimeException | LinkageError e) {
                // the raw destination stands
            }
            GCMovement.move(c, want.x, want.y);
        } catch (RuntimeException | LinkageError e) {
            // the walk is best effort
        }
    }

    /** A social or town bot has no brain that loaded its life memory: the talk tick's own loader does (as for its notes). */
    @Override
    public boolean mindReady(int charId) {
        try {
            return soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore.peek(charId) != null
                    || BotTalk.memoryOf(charId) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The social level of detail: a watched bot decides at the normal pace, an unwatched one four times less often. */
    @Override
    public double paceFactor(int charId) {
        try {
            return switch (soloMapling.ArtificialPlayer.BotSocialSystem.SocialLod.tierOf(sm(charId))) {
                case ACTIVE -> 1.0;
                case BACKGROUND -> 4.0;
                case DORMANT -> Double.POSITIVE_INFINITY;
            };
        } catch (RuntimeException | LinkageError e) {
            return 1.0;
        }
    }

    @Override
    public boolean sameParty(int a, int b) {
        try {
            Character x = chr(a), y = chr(b);
            return x != null && y != null && x.getParty() != null && y.getParty() != null
                    && x.getParty().getId() == y.getParty().getId();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public List<BotMemory.Note> recall(int botCharId, int subjectId, BotMemory.Query query, long now, int limit) {
        try {
            return BotMemory.recallIfCached(botCharId - BotStore.BOT_ID_BASE, subjectId, query, now, limit);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    @Override
    public void remember(int botCharId, int subjectId, BotMemory.Kind kind, String text, double valence, int mapId,
                         long now) {
        BotEpisodes.botEvent(botCharId, subjectId, kind, text, valence, mapId, now);
    }
}
