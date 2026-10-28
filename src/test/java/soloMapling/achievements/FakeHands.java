package soloMapling.achievements;

import client.Character;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bag with room for {@code room} more medals, and a record of what was said and announced. */
class FakeHands implements Hands {
    final Set<Integer> bag = new HashSet<>();
    int room = 100;
    final List<String> told = new ArrayList<>();
    final List<String> announced = new ArrayList<>();
    int gives;
    int saves;

    @Override
    public boolean has(Character chr, int medal) {
        return bag.contains(medal);
    }

    @Override
    public boolean give(Character chr, Goal goal) {
        if (room <= 0) {
            return false;
        }
        room--;
        gives++;
        bag.add(goal.medal());
        return true;
    }

    @Override
    public void save(Character chr) {
        saves++;
    }

    @Override
    public void tell(Character chr, String text) {
        told.add(text);
    }

    @Override
    public String name(int medal) {
        return "Medal " + medal;
    }

    @Override
    public void announce(Character chr, Goal goal) {
        announced.add(goal.id());
    }
}
