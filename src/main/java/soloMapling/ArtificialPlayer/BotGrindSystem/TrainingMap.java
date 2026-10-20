package soloMapling.ArtificialPlayer.BotGrindSystem;

// A candidate training map: its id, its representative mob level, and its hop distance from the bot's
// spawn town (1 = adjacent). Produced by TrainingMapFinder. relocateHomeMapId (ticket 03) is the hub town of
// another region when the chooser picked the map there: the bot travels over and makes that hub its home.
// -1 = a map around the bot's own region.
public record TrainingMap(int mapId, int mobLevel, int hops, int relocateHomeMapId) {

    public TrainingMap(int mapId, int mobLevel, int hops) {
        this(mapId, mobLevel, hops, -1);
    }

    public boolean relocates() {
        return relocateHomeMapId > 0;
    }

    TrainingMap relocatingTo(int hubMapId) {
        return new TrainingMap(mapId, mobLevel, hops, hubMapId);
    }
}
