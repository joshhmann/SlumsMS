let status;
let tempsel;
let choice;
let restart;
let type;

let selectedItem;

let item;
let amount;
let takeOut = false;

function start() {
    status = 0;
    restart = false;

    var str = "\r\n\r\nHello! #r#h ##k";
    str += ", I am your personal Adventurer's Bag!\r\nWhat would you like to do?\r\n#b" +
        "#L0#I want to store an item.#l\r\n" +
        "#L3#Retrieve an item.#l\r\n\r\n\r\n" +
        "#L4#Deposit all ores.#l\r\n" +
        "#L5#Withdraw all ores.#l\r\n";
    cm.sendSimple(str);
}

function action(m, t, s) {
    if (m !== 1) {
        cm.sendOk("See you soon!");
        cm.dispose();
    } else {
        if (restart) {
            start();
            return;
        }
        status++;
        noItemStored(m, t, s);
    }
}

let restrictedItems = [
    2070000, 2070001, 2070002, 2070003, 2070004, 2070005, 2070006, 2070007, 
    2070008, 2070009, 2070010, 2070011, 2070012, 2070013, 2070015, 2070016, 
    2070018, 2330000, 2330001, 2330002, 2330003, 2330004, 2330005
];

let autoStoreItems = [
    4020007, 4020005, 4020000, 4020004, 4020001, 4020002, 4020006, 4020003, 
    4004000, 4004001, 4004003, 4004002, 4020008, 4004004, 
    4010000, 4010001, 4010002, 4010003, 4010004, 4010005, 4010006, 4010007,
    4007000, 4007001, 4007002, 4007003, 4007004, 4007005, 4007006, 4007007, 
    4011007, 4021009, 4032133, 4021010, 4001094, 4001693, 4000659
];

function noItemStored(m, t, s) {
    if (status === 1) {
        if (s === 0) { // Store manually
            takeOut = false;
            cm.sendSimple("Please, select a category to deposit:\r\n#b" +
                "#L0#Use#l\t\t" +
                "#L1#Etc#l\t\t" +
                "#L2#Setup#l\t");
        } else if (s === 3) { // Retrieve manually
            takeOut = true;
            cm.sendSimple("Please, select a category to withdraw from:\r\n#b" +
                "#L0#Use#l\t\t" +
                "#L1#Etc#l\t\t" +
                "#L2#Setup#l\t");
        } else if (s === 4) { // Auto-store ores/crystals
            let storedAny = false;
            let msg = "I've stored the following items:\r\n";

            for (let i = 0; i < autoStoreItems.length; i++) {
                let id = autoStoreItems[i];
                if (restrictedItems.includes(id)) continue;

                // Plain stacks only: bound, expiring or tagged stacks stay in the inventory.
                let quantity = cm.bagDepositable(id);
                if (quantity > 0 && cm.bagDeposit(id, quantity) == "OK") {
                    msg += "#i" + id + ":# #b#t" + id + "##k x" + quantity + "\r\n";
                    storedAny = true;
                }
            }

            if (storedAny) {
                cm.sendOk(msg);
            } else {
                cm.sendOk("You don't have any ores or crystals to store.");
            }
            cm.dispose();
        } else if (s === 5) { // Auto-withdraw ores/crystals
            let withdrawnAny = false;
            let msg = "Withdrawn the following items:\r\n";

            for (let i = 0; i < autoStoreItems.length; i++) {
                let id = autoStoreItems[i];
                let storedAmount = cm.getStoredItemAmount(cm.getPlayer().getId(), id);

                if (storedAmount > 0) {
                    // As much as fits, moved in one step (the bag and the inventory change together).
                    let amountToWithdraw = cm.bagWithdrawUpTo(id, storedAmount);
                    if (amountToWithdraw <= 0) continue;

                    msg += "#i" + id + ":# #b#t" + id + "##k x" + amountToWithdraw + "\r\n";
                    withdrawnAny = true;
                }
            }

            if (withdrawnAny) {
                cm.sendOk(msg);
            } else {
                cm.sendOk("You don’t have any ores or crystals stored.");
            }
            cm.dispose();
        }
} else if (status === 2) {
    tempsel = s;

    let list = null;

    if (!takeOut) {
        if (s === 0) list = cm.getInventoryAsString("Consume");
        else if (s === 1) list = cm.getInventoryAsString("Etc");
        else if (s === 2) list = cm.getInventoryAsString("Install");
    } else {
        if (s === 0) list = cm.formatStoredItems("Consume");
        else if (s === 1) list = cm.formatStoredItems("Etc");
        else if (s === 2) list = cm.formatStoredItems("Setup");
    }

    // 🔥 REAL FIX
    if (!list || !list.includes("#L")) {
        cm.sendOk("You don't have any items.");
        cm.dispose();
        return;
    }

    cm.sendSimple(list);
} else if (status === 3) {
        if (!takeOut) {
            if (tempsel === 0) item = cm.getItemByTypeAndPos("Consume", s, cm.getPlayer());
            else if (tempsel === 1) item = cm.getItemByTypeAndPos("Etc", s, cm.getPlayer());
            else if (tempsel === 2) item = cm.getItemByTypeAndPos("Setup", s, cm.getPlayer());

            if (item !== null) {
                selectedItem = item.getItemId();

                if (restrictedItems.includes(selectedItem)) {
                    cm.sendOk("This item cannot be stored.");
                    restart = true;
                    return;
                }

                let max = Math.min(cm.bagDepositable(selectedItem), 32000);
                if (max <= 0) {
                    cm.sendOk("That item can't go in the bag: it is bound, expiring, tagged or a quest item.");
                    restart = true;
                    return;
                }
                cm.sendGetNumber("How much of #i" + selectedItem + ":# #b#t" + selectedItem + "##k do you wish to store?\r\n(max: #r" + max + "#k)", max, 1, max);
            } else {
                cm.sendOk("Item not found. Please try again.");
                restart = true;
            }
        } else {
            type = (tempsel === 0 ? "Consume" : tempsel === 2 ? "Setup" : "Etc");
            item = cm.getSelectedStoredItemId(s, type);

            let currentStoredAmount = cm.getStoredItemAmount(cm.getPlayer().getId(), item);
            amount = currentStoredAmount;

            cm.sendGetNumber("\r\n\r\nHow much of #i" + item + ":# #b#t" + item + "##k do you wish to retrieve?\r\n(stored: #r" + amount + "#k)", amount, 1, amount);
        }
    } else if (status === 4) {
        if (!takeOut) {
            if (s > 32000) {
                cm.sendOk("You cannot store more than 32,000 items at once.");
                restart = true;
                return;
            }
            if (antiCheatCheck(selectedItem, s, cm.itemQuantity(selectedItem))) {
                let result = cm.bagDeposit(selectedItem, s);
                if (result == "OK") {
                    cm.sendOk("I've stored #r" + s + "#k #t" + selectedItem + "#" + (s === 1 ? "" : "s") + ".");
                } else {
                    cm.sendOk(bagRefusal(result));
                }
                restart = true;
            }
        } else {
            if (antiCheatCheck(item, s, amount)) {
                let result = cm.bagWithdraw(item, s);
                cm.sendOk(result == "OK" ? "Your item has been retrieved." : bagRefusal(result));
                restart = true;
            }
        }
    }
}

function bagRefusal(result) {
    if (result == "NO_SPACE") return "You do not have enough inventory space available.";
    if (result == "NOT_ALLOWED") return "That item can't go in or out of the bag like that: it is bound, expiring, tagged, a quest item or one-of-a-kind.";
    if (result == "BAG_FULL") return "The bag can't hold any more of that item.";
    if (result == "NOT_ENOUGH") return "You don't have that many.";
    return "That amount doesn't work.";
}

function antiCheatCheck(item, amount, max) {
    if (amount > max) {
        cm.getPlayer().autoban(cm.getName() + " tried to packet edit the Infinity Storage (exceeding max input)");
        return false;
    } else if (amount < 1) {
        cm.getPlayer().autoban(cm.getName() + " tried to packet edit the Infinity Storage (negative input)");
        return false;
    }
    return true;
}