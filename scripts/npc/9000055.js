/**
 * Casino Chip Exchange NPC: 9000055 ("Aramia"), spawned in Henesys Game Park (map 100000203, 1321/214) by
 * EnvironmentManager.spawnCasinoNpcs. Opens the chip shop where players buy and sell the four stamp chips
 * (4002000-4002003) at equal prices, a lossless meso exchange.
 *
 * Shop 9999001 must exist in the database (shops + shopitems): database/migrations/059-casino-chip-shop.sql inserts
 * it. Without that row openShopNPC logs "Shop ID: 9999001 is missing from database." and opens GM shop 11000 instead.
 */
function start() {
    cm.openShopNPC(9999001);
    cm.dispose();
}
