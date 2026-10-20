package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.Skill;
import client.SkillFactory;
import server.ItemInformationProvider;
import server.StatEffect;

/*
 * Real bots 08: the WZ numbers the supply code reads - what a potion restores, how many projectiles a skill fires,
 * what a skill costs - behind one seam, so the rules can be tested without the server's item and skill data.
 * Every read degrades to "nothing" (no recovery, one projectile, no cost) when the data is not there.
 */
final class SupplyData {

    private SupplyData() {}

    /* What a recovery item restores: flat HP/MP plus a share of the max (hpR/mpR, 0..1). */
    record Recovery(int hp, int mp, double hpRate, double mpRate) {
        boolean restoresHp() {
            return hp > 0 || hpRate > 0;
        }

        boolean restoresMp() {
            return mp > 0 || mpRate > 0;
        }
    }

    interface Source {
        /* The item's recovery when it is a plain one (a potion or food with no buff), else null. */
        Recovery recovery(int itemId);

        /* The skill's WZ effect numbers at this level. */
        int bulletCount(int skillId, int level);

        int bulletConsume(int skillId, int level);

        int mpCon(int skillId, int level);

        int hpCon(int skillId, int level);

        int x(int skillId, int level);

        /* The skill's buff duration in ms (0 when it has none). */
        int duration(int skillId, int level);

        /* The weapon attack a projectile adds (its incPAD). */
        int projectileWatk(int itemId);

        /*
         * Ticket 09: a stack's size for this character, as the server's shop gives it (ItemInformationProvider
         * getSlotMax with the player's Claw/Gun Mastery); 0 when there is no item data.
         */
        default int slotMax(Character bot, int itemId) {
            try {
                return Math.max(0, ItemInformationProvider.getInstance().getSlotMax(bot, itemId));
            } catch (LinkageError | RuntimeException unavailable) {
                return 0;
            }
        }

        /* Ticket 09: what one star or bullet costs to recharge (the item's WZ unitPrice); 0 when unknown. */
        default double unitPrice(int itemId) {
            try {
                return Math.max(0, ItemInformationProvider.getInstance().getUnitPrice(itemId));
            } catch (LinkageError | RuntimeException unavailable) {
                return 0;
            }
        }
    }

    static final Source SERVER = new Source() {
        @Override
        public Recovery recovery(int itemId) {
            int family = itemId / 10000;
            if (family < 200 || family > 202) {
                return null;   // potions (200), and the foods and drinks that restore (201, 202)
            }
            StatEffect e = itemEffect(itemId);
            if (e == null || !e.getStatups().isEmpty()) {
                return null;   // unknown, or a buff item rather than a recovery one
            }
            Recovery r = new Recovery(Math.max(0, e.getHp()), Math.max(0, e.getMp()),
                    Math.max(0, e.getHpRate()), Math.max(0, e.getMpRate()));
            return r.restoresHp() || r.restoresMp() ? r : null;
        }

        @Override
        public int bulletCount(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? e.getBulletCount() : 1;
        }

        @Override
        public int bulletConsume(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? e.getBulletConsume() : 0;
        }

        @Override
        public int mpCon(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? Math.max(0, e.getMpCon()) : 0;
        }

        @Override
        public int hpCon(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? Math.max(0, e.getHpCon()) : 0;
        }

        @Override
        public int x(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? e.getX() : 0;
        }

        @Override
        public int duration(int skillId, int level) {
            StatEffect e = skillEffect(skillId, level);
            return e != null ? Math.max(0, e.getDuration()) : 0;
        }

        @Override
        public int projectileWatk(int itemId) {
            try {
                return Math.max(0, ItemInformationProvider.getInstance().getWatkForProjectile(itemId));
            } catch (LinkageError | RuntimeException unavailable) {
                return 0;
            }
        }
    };

    private static volatile Source source = SERVER;

    static Source get() {
        return source;
    }

    /* Tests swap the numbers in; null puts the server's back. */
    static void useForTest(Source test) {
        source = test != null ? test : SERVER;
    }

    private static StatEffect itemEffect(int itemId) {
        try {
            return ItemInformationProvider.getInstance().getItemEffect(itemId);
        } catch (LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    private static StatEffect skillEffect(int skillId, int level) {
        if (skillId == 0 || level <= 0) {
            return null;
        }
        try {
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null) {
                return null;
            }
            int max = skill.getMaxLevel();
            return skill.getEffect(max > 0 ? Math.min(level, max) : level);
        } catch (LinkageError | RuntimeException unavailable) {
            return null;
        }
    }
}
