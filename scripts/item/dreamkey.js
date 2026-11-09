/*  Author: Rulax
	Item Name: 		Thunder Stone (2430200)
	Description: 	Consumes stones to create Dream Key
*/

var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        im.dispose();
    } else {
        if (mode == 0 && type > 0) {
            im.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            // 1.6.8 (owner, 9 Nov 15:26 UTC): the recipe stays ours - 5 Dawn, 5 Blaze, 5 Wind, 5 Night and the Thunder
            // Stone that runs this script - not the reference server's 1 of each. Every ingredient is checked before
            // anything is taken, so a missing stone costs nothing.
            if (!im.hasItem(4000660, 5) || !im.hasItem(4000661, 5) || !im.hasItem(4000662, 5) || !im.hasItem(4000663, 5)
                || !im.hasItem(2430200))
            {
                im.playerMessage(5, "You need 5 of each stone - Dawn, Blaze, Wind and Night - and the Thunder Stone to make a Dream Key.");
                im.dispose();
                return;
            }
            if (!im.canHold(4032923)) {   // 1.6.8: a full ETC tab used to eat the stones and give nothing
                im.playerMessage(5, "Make room in your ETC tab first; your stones are still with you.");
                im.dispose();
                return;
            }
            im.gainItem(4000660, -5);
            im.gainItem(4000661, -5);
            im.gainItem(4000662, -5);
            im.gainItem(4000663, -5);
            im.gainItem(2430200, -1);
            im.gainItem(4032923, 1);
            im.playerMessage(5, "You have obtained the Dream Key.");
        }
    }
    im.dispose();
}
