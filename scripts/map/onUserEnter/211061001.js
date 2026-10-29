/*
 * Roof of the Fifth Tower (211061001). Its map data carries qrLimit = 3141 (so does the Gardener's Spot, 211080000): a
 * quest-record gate the client may check before it lets the roof's next00 portal (pt_rosegarden) send anything. Quest
 * record 3141 has no quest data (an info record), and nothing on this server ever set it, so pressing Up on next00 did
 * nothing in a real client (1.6.1, owner 2026-10-29). Standing on the roof means the towers' keys are behind you, so the
 * server sets the record here; Ifia (2161001) on the roof also walks you into the Rose Garden if the portal still won't.
 */
function start(ms) {
    if (ms.getQuestStatus(3141) == 0) {
        ms.forceStartQuest(3141);
    }
}
