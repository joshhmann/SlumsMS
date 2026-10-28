package soloMapling.achievements;

import client.Character;

/** The game side of an award: the bag, messages, the public feed. A test supplies its own. */
public interface Hands {
    /** Whether the character already owns the medal (bag or worn). */
    boolean has(Character chr, int medal);

    /** Puts a zero-stat copy of the medal in the bag; false when there is no room (nothing was added). */
    boolean give(Character chr, Goal goal);

    /** Writes the inventory to the database, so a granted medal and its row cannot disagree after a crash. */
    void save(Character chr);

    void tell(Character chr, String text);

    /** The medal's name as the client shows it. */
    String name(int medal);

    /** The goal was earned for real (not seeded, not a GM grant): the website's "recent achievements". */
    void announce(Character chr, Goal goal);
}
