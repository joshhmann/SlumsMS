/*
 * SlumsMS 1.5.0: the Cash Shop window's partner-name prompt for couple and friendship rings.
 * Started by server.cashshop.CashShopPartnerRings.begin when the player presses Buy on a partner ring;
 * the purchase itself is CashShopPartnerRings.complete. Closing this chat cancels the ring (no charge).
 */
var Rings = Java.type('server.cashshop.CashShopPartnerRings');
var status = -1;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;
    if (status == 0) {
        var itemId = Rings.pendingItem(cm.getPlayer());
        if (itemId == 0) {
            cm.sendOk("There is no ring purchase waiting. Press Buy in the Cash Shop again.");
            status = 9;
            return;
        }
        var kind = Rings.isCoupleRing(itemId) ? "couple" : "friendship";
        cm.sendGetText("#v" + itemId + "# #b#t" + itemId + "##k is a " + kind + " ring: your partner gets the other one.\r\n"
            + "Type your partner's character name. They must be online.");
    } else if (status == 1) {
        var line = Rings.complete(cm.getClient(), cm.getPlayer(), cm.getText());
        cm.sendOk(line);
    } else {
        cm.dispose();
    }
}
