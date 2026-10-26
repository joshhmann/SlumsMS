package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.worldevents.ActiveEvent;
import soloMapling.worldevents.EventScheduler;
import soloMapling.worldevents.EventTemplate;
import soloMapling.worldevents.EventTemplates;
import soloMapling.worldevents.WorldEvents;

import java.util.Map;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * Scripted world events (ticket 21). Every use is written to the GM audit log (GmAudit, ticket 43).
 * !event [list]               - templates (with their slot: headline, small, manual), what is running
 * !event next                 - 1.6.0: today's plan (the 8 PM headline and its template, the small events' times)
 * !event preview [id]         - where a template can run and which bot levels go
 * !event start <id> [mapId]   - start one now (at most 2 at once)
 * !event stop [eventNo|all]   - end one now, or all of them (its mobs and NPC leave, no reward unless already won)
 * !event auto on|off          - switch the director (the daily plan) until the next restart
 * !event reload               - re-read WorldEvents.yaml
 */
public class WorldEventCommand extends Command {
    {
        setDescription("World events: !event list | next | preview [id] | start <id> [map] | stop [no|all] | auto on|off | reload");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        WorldEvents events = WorldEvents.getInstance();
        long now = System.currentTimeMillis();
        String sub = params.length == 0 ? "list" : params[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                EventScheduler.Settings s = events.settings();
                player.yellowMessage("World events: director " + (s.auto() ? "ON" : "OFF") + " (a headline every"
                        + " evening, small ones at peak hours; max " + EventScheduler.MAX_ACTIVE_CAP
                        + " at once). !event next shows today's plan.");
                Map<String, Long> ended = events.lastEndedAt();
                for (EventTemplate t : EventTemplates.get().templates()) {
                    Long last = ended.get(t.id());
                    long rest = last == null ? 0 : Math.max(0, (last + t.cooldownMs() - now) / 60_000);
                    player.yellowMessage(" " + t.id() + " - " + t.kind().label() + ", "
                            + t.slot().name().toLowerCase() + ", w" + t.weight() + ", "
                            + t.minutes() + " min" + (t.enabled() ? "" : ", disabled")
                            + (rest > 0 ? ", resting " + rest + " min" : ""));
                }
                for (String err : EventTemplates.get().errors()) {
                    player.yellowMessage(" skipped: " + err);
                }
                if (events.active().isEmpty()) {
                    player.yellowMessage("Nothing running.");
                }
                for (ActiveEvent e : events.active()) {
                    player.yellowMessage("Running " + e.describe(now));
                }
            }
            case "next", "schedule" -> events.next(now).forEach(player::yellowMessage);
            case "preview" -> {
                if (params.length > 1) {
                    events.preview(params[1]).forEach(player::yellowMessage);
                } else {
                    events.next(now).forEach(player::yellowMessage);
                }
            }
            case "start" -> {
                if (params.length < 2) {
                    player.yellowMessage("Usage: !event start <id> [mapId]");
                    return;
                }
                int mapId = params.length > 2 && isInteger(params[2]) ? Integer.parseInt(params[2]) : 0;
                WorldEvents.StartResult r = events.startById(params[1], mapId, player.getName());
                player.yellowMessage(r.error() != null ? "Not started: " + r.error() : "Started " + r.event().describe(now));
            }
            case "stop" -> {
                if (params.length < 2 || params[1].equalsIgnoreCase("all")) {
                    player.yellowMessage("Stopped " + events.stopAll(player.getName()) + " events.");
                } else if (isInteger(params[1].replace("#", ""))) {
                    boolean ok = events.stop(Integer.parseInt(params[1].replace("#", "")), player.getName());
                    player.yellowMessage(ok ? "Stopped." : "No running event #" + params[1]);
                } else {
                    player.yellowMessage("Usage: !event stop <eventNo|all>");
                }
            }
            case "auto" -> {
                if (params.length < 2 || !(params[1].equalsIgnoreCase("on") || params[1].equalsIgnoreCase("off"))) {
                    player.yellowMessage("Usage: !event auto on|off");
                    return;
                }
                events.setAuto(params[1].equalsIgnoreCase("on"));
                player.yellowMessage("World event director " + (events.settings().auto() ? "ON" : "OFF")
                        + " until the next restart.");
            }
            case "reload" -> {
                EventTemplates.Result r = EventTemplates.reload();
                player.yellowMessage("Loaded " + r.templates().size() + " templates, " + r.errors().size() + " problems.");
                r.errors().forEach(err -> player.yellowMessage(" " + err));
            }
            default -> player.yellowMessage(getDescription());
        }
    }
}
