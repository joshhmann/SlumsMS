function enter(pi) {
    pi.setDirectionStatus(true);
    pi.lockUI2();
    pi.spawnNpc(579711, -51, -97);//stock: spawnNPC(579711, 1096012, -51, -97, 0, true) - only the position is ours to place
    pi.setNPCValue(579711, "summon");
    pi.updateInfo("fly", "579711");
    pi.sendDirectionInfo(3, 0);
    pi.sendDirectionInfo(3, 2);
    pi.sendDirectionInfo(4, 0);
    return true;
}