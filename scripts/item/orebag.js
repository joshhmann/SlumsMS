var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    // 1.6.4: this server has no ore-bag storage window - the only Storage is the bank the Storage Keeper opens -
    // and an item script has no dialog to open one with. Say it plainly instead of calling a method that does not
    // exist: nothing is taken out of the bag, and nothing is lost.
    im.dropMessage(5, "The ore bag is not open for business on this server yet. Nothing was taken out of it.");
}
