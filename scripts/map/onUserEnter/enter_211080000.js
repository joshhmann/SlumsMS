/*
 * Gardener's Spot (211080000, the Rose Garden's entrance; the WZ names this onUserEnter script). Its map data has
 * qrLimit = 3141 like the Fifth Tower's roof, so the way back (west00, pt_rosegardenout) gets the same quest record
 * (see onUserEnter/211061001.js; 1.6.1, owner 2026-10-29). Anyone who reached the garden by Hyper Rock gets it here.
 */
function start(ms) {
    if (ms.getQuestStatus(3141) == 0) {
        ms.forceStartQuest(3141);
    }
}
