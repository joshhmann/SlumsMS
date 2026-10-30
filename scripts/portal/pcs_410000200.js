/*
 * Marketplace (410000200), portal pcs00: a range trigger (portal type 9, 700 x 500 around (-1086, -216), every 6 s)
 * from a later client's map. It fires on its own while a character stands near it, and the v83 game has nothing for
 * it to do. 1.6.2 b089: without this file every firing logged "refused: no script file". It does nothing.
 */
function enter(pi) {
    return false;
}
