package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActKind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialFlows;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Offer;
import soloMapling.ArtificialPlayer.BotWorldGossip;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The real flows behind {@link SocialFlows} (ticket 271 stage 4): each wrapped social act runs the existing
 * bot-to-bot path without changing it. GREET and CONVERSE are {@link BotTalk#run} (a greeting-only plan, a full chat
 * plan), ASK_HELP is {@code run} with the HELP ask forced (the other's answer is {@link TalkBrain}'s), OFFER_HELP is the
 * help trip the HELP act already ran ({@link BotTalk#helpTrip}), INVITE/ACCEPT use {@link LfgParty} and the plain duo
 * party or the come-along trips ({@link BotTalk#plainParty}, {@link BotTalk#comeAlong}), SHARE_INFO and SPREAD_RUMOR are
 * the {@link BotWorldGossip} and {@link BotGossip} passes. Lives in the talk package for their package-private parts.
 */
public final class GameSocialFlows implements SocialFlows {

    private static Character chr(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        return sm == null ? null : sm.getChr();
    }

    @Override
    public String blocker(SocialActKind kind, int actorId, int targetId, long now) {
        Character a = chr(actorId);
        Character b = chr(targetId);
        if (a == null || b == null || a.getMap() == null || b.getMap() == null || a.getMapId() != b.getMapId()) {
            return "not on one map";
        }
        try {
            switch (kind) {
                case GREET:
                    return null;
                case CONVERSE:
                    if (BotThreads.mapWeight(a.getMap()) <= 0) {
                        return "no chatting on this kind of map";
                    }
                    return BotThreads.talker(CharacterStorage.getBotById(actorId))
                            && BotThreads.talker(CharacterStorage.getBotById(targetId)) ? null : "not both free to chat";
                case ASK_HELP:
                    return helpOffered(a, b) ? null : "no help to ask for here";
                case OFFER_HELP:
                    return helpOffered(b, a) && grinding(a) ? null : "nothing to help with here";
                case INVITE: {
                    LfgParty.State sa = LfgParty.stateOf(a, now);
                    LfgParty.State sb = LfgParty.stateOf(b, now);
                    if (!free(sa) || !free(sb)) {
                        return "one of them is partied or busy";
                    }
                    return BotTalk.anInviteToMake(a, now) ? null : "nothing to invite them to";
                }
                case SHARE_INFO:
                    return sharable(actorId, targetId, now, false) ? null : "nothing to tell";
                case SPREAD_RUMOR:
                    return sharable(actorId, targetId, now, true) ? null : "no rumor to pass on";
                default:
                    return "not a wrapped act";
            }
        } catch (RuntimeException | LinkageError e) {
            return "could not check";
        }
    }

    private static boolean free(LfgParty.State s) {
        return !s.partied() && !s.busy() && !s.afk() && !s.shop() && !s.owned();
    }

    /** The HELP ask's own rule: {@code asker} grinds a mob map with a stronger bot (5-20 levels above) near. */
    private static boolean helpOffered(Character asker, Character helper) {
        return BotTalk.lifeOf(asker.getId()) != null && BotTalk.lifeOf(helper.getId()) != null
                && helper.getLevel() >= asker.getLevel() + 5 && helper.getLevel() <= asker.getLevel() + 20
                && asker.getMap() != null && !asker.getMap().isTown() && !asker.getMap().getAllMonsters().isEmpty();
    }

    private static boolean grinding(Character helper) {
        LifeRuntime life = BotTalk.lifeOf(helper.getId());
        return life != null && "GRIND".equals(life.phase());
    }

    /** Whether {@code teller} holds a story {@code listener} would keep (negative ones only for a rumor). */
    private static boolean sharable(int tellerId, int listenerId, long now, boolean rumor) {
        if (!rumor && BotWorldGossip.canPass(tellerId, listenerId, now)) {
            return true;
        }
        for (BotGossip.Heard h : BotGossip.heard(tellerId, now)) {
            if (h.fact().negative() == rumor && h.fact().subjectId() != listenerId
                    && BotGossip.wouldKeep(listenerId, tellerId, h.fact(), now)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Flow run(SocialActKind kind, int actorId, int targetId, long now) {
        Character a = chr(actorId);
        Character b = chr(targetId);
        if (a == null || b == null || a.getMap() == null || a.getMapId() != b.getMapId()) {
            return Flow.no("not on one map");
        }
        Random rng = SocialSim.random(actorId, "social-flow");
        switch (kind) {
            case GREET: {
                TalkPlanner.Plan plan = BotTalk.run(a.getMap(), List.of(a, b), true, null, false,
                        "decided to say hi (social act)", now, rng);
                return plan != null ? Flow.yes("greeted " + b.getName()) : Flow.no("the greeting could not start");
            }
            case CONVERSE: {
                TalkPlanner.Plan plan = BotTalk.run(a.getMap(), List.of(a, b), false, null, false,
                        "decided to chat (social act)", now, rng);
                return plan != null ? Flow.yes("chatting with " + b.getName() + ": " + plan.summary())
                        : Flow.no("the chat could not start");
            }
            case ASK_HELP: {
                TalkPlanner.Plan plan = BotTalk.run(a.getMap(), List.of(a, b), false, Ask.HELP, false,
                        "decided to ask for help (social act)", now, rng);
                return plan != null && plan.ask == Ask.HELP ? Flow.yes("asked " + b.getName() + " for help: "
                        + plan.summary()) : Flow.no(plan == null ? "the talk could not start" : "no help was asked");
            }
            case OFFER_HELP: {
                BotTalk.Result r = BotTalk.helpTrip(b, a, BotThreadsView.mapName(a), a.getMapId(), now);
                return r.ok() ? Flow.yes(r.text()) : Flow.no(r.text());
            }
            case SHARE_INFO: {
                if (BotWorldGossip.enabled() && BotWorldGossip.pass(actorId, targetId, now, rng) != null) {
                    return Flow.yes("told " + b.getName() + " the news");
                }
                for (BotGossip.Heard h : BotGossip.heard(actorId, now)) {
                    if (!h.fact().negative() && h.fact().subjectId() != targetId
                            && BotGossip.keep(targetId, actorId, a.getName(), h.fact(), now) != null) {
                        return Flow.yes("told " + b.getName() + " " + h.fact().about());
                    }
                }
                return Flow.no("nothing new to tell");
            }
            case SPREAD_RUMOR: {
                for (BotGossip.Heard h : BotGossip.heard(actorId, now)) {
                    if (h.fact().negative() && h.fact().subjectId() != targetId
                            && BotGossip.keep(targetId, actorId, a.getName(), h.fact(), now) != null) {
                        return Flow.yes("passed on a rumor to " + b.getName());
                    }
                }
                return Flow.no("the listener already knew it");
            }
            default:
                return Flow.no("not a wrapped act");
        }
    }

    @Override
    public SocialActions.Invite propose(int actorId, int targetId, long now) {
        Character a = chr(actorId);
        Character b = chr(targetId);
        if (a == null || b == null || a.getMap() == null) {
            return null;
        }
        BotTalk.WorldSense sense = new BotTalk.WorldSense();
        Map<Ask, Offer> offers = sense.offers(a, b, false, now, SocialSim.random(actorId, "social-flow"));
        Offer pick = invitation(offers);
        if (pick == null || pick.payload() != sense.reserved) {
            LfgParty.release(sense.reserved); // a PQ slot reserved for an offer that was not the one made
        }
        if (pick == null) {
            return null;
        }
        String what = pick.ask() == Ask.COME_ALONG ? "a trip to " + pick.about()
                : pick.slots() != null && pick.slots().containsKey("pq") ? "a PQ party (" + pick.about() + ")" : "a duo party";
        return new SocialActions.Invite(0, actorId, targetId, what, a.getMapId(), now, 0, pick);
    }

    /** Which of {@code offers} an INVITE makes, or null for none. */
    static Offer invitation(Map<Ask, Offer> offers) {
        Offer party = offers.get(Ask.PARTY);
        return party != null ? party : offers.get(Ask.COME_ALONG);
    }

    @Override
    public Flow accept(SocialActions.Invite invite, long now) {
        Character a = chr(invite.from());
        Character b = chr(invite.to());
        if (a == null || b == null || a.getMapId() != b.getMapId() || !(invite.payload() instanceof Offer offer)) {
            return Flow.no("one of them left");
        }
        BotTalk.Result r;
        if (offer.ask() == Ask.PARTY) {
            if (offer.payload() instanceof LfgParty.Plan lfg) {
                if (!lfg.yes()) {
                    return Flow.no("LFG rules said no: " + lfg.decline());
                }
                boolean formed = LfgParty.form(lfg, invite.mapId(), 0);
                r = new BotTalk.Result(formed, formed ? "lfg" : "lfg-drop", formed ? "PQ party formed and both trips handed over"
                        : "the LFG party did not form");
            } else {
                r = BotTalk.plainParty(a, List.of(b), invite.mapId());
            }
        } else if (offer.ask() == Ask.COME_ALONG && offer.payload() instanceof Integer town) {
            r = BotTalk.comeAlong(a, List.of(b), town, now);
        } else {
            return Flow.no("not an invitation this knows");
        }
        return r.ok() ? Flow.yes(r.text()) : Flow.no(r.text());
    }

    @Override
    public void release(SocialActions.Invite invite) {
        if (invite != null && invite.payload() instanceof Offer o && o.payload() instanceof LfgParty.Plan p) {
            LfgParty.release(p);
        }
    }

    /** Small shim: the map's short name, as the HELP ask words it. */
    private static final class BotThreadsView {
        static String mapName(Character c) {
            try {
                return c.getMap() == null ? "here" : c.getMap().getMapName();
            } catch (RuntimeException e) {
                return "here";
            }
        }
    }
}
