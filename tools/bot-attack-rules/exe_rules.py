#!/usr/bin/env python3
"""Read what MapleRoot's v83 MapleStory.exe checks in another character's attack, and how its own player sends one,
straight out of the exe (docs/real-bots/client-remote-attack-rules.md has the addresses and the pseudocode).

Needs:  pip install pefile capstone unicorn
    python exe_rules.py --exe MapleStory.exe --skills client-attack-data.tsv \
        --mapleroot-routes <client repo>/mapleroot/ijl15/SkillEdits/Activeskill.h > client-attack-exe.tsv

Rows (tab separated):
    A <code> <name>                          the body-action table (0x4a38e7 builds it at 0xbec620, 24-byte entries)
    T <T1|T2|T3> <attackType> <norm|type3> <codes,...>   plain poses per weapon "attack" type (melee/shoot/magic)
    K <skill> <flags> <route> <weaponTypes>  per client skill: flags from the exe's hard-coded skill lists, the route
                                             the local player's DoActiveSkill sends it on, and the weapon types the
                                             exe accepts it with (SKILLENTRY::IsCorrectWeaponType, 0x766157)
flags: keydown (is_keydown_skill 0x4fb08f), noActionCheck (OnAttack skips CheckAttackAction, 0x980722),
       keepPose (OnAttack keeps the keydown pose, 0x98044f), noEffect (OnAttack skips the skill effect, 0x98076d),
       shootWithoutWeapon (0x76681d), shootNoBullet (0x7668b7)
route: melee | shoot | magic | keydown | buff | other:<handler> | none (the default branch: not an attack)
"""
import argparse
import hashlib
import re
import struct
import sys

import capstone
import pefile
from unicorn import UC_ARCH_X86, UC_HOOK_CODE, UC_MODE_32, Uc
from unicorn.x86_const import UC_X86_REG_EAX, UC_X86_REG_EBP, UC_X86_REG_ECX, UC_X86_REG_EIP, UC_X86_REG_ESI, \
    UC_X86_REG_ESP

ACTION_TABLE, ACTION_COUNT = 0xBEC620, 162
TABLES = (("T1", 0xBDAFD8, 24, 5), ("T2", 0xBDB188, 16, 3), ("T3", 0xBDB2A8, 12, 2))
# DoActiveSkill's per-skill switch (0x966F7A): the handler each branch calls
HANDLERS = {0x969465: "melee", 0x9696A2: "shoot", 0x969B10: "magic", 0x969E21: "buff", 0x96A86E: "keydown",
            0x969B55: "magic", 0x969A57: "shoot", 0x96A69D: "magic",
            0x96C215: "other:magnet", 0x96C917: "other:0x96c917"}
# OnAttack's inline skill lists (CUserRemote::OnAttack 0x9803AB); verified present in the code below
ON_ATTACK_LISTS = {
    "keepPose": (0x980452, 0x98046C, (3121004, 5221004, 15111006, 13111002)),
    "noActionCheck": (0x980710, 0x980752, (2301002, 3121004, 5221004, 5201002, 15111006, 13111002, 14111006)),
    "noEffect": (0x980710, 0x9807A6, (3121004, 5221004, 5201002, 5201006, 15111006, 13111002, 13101002, 14111006)),
}
MAPLEROOT_ROUTES = {"melee": "melee", "shoot": "shoot", "magic": "magic", "buff": "buff", "prepare": "keydown",
                    "summons": "other:summon", "combat": "other:combatStep", "recover": "other:recovery",
                    "jumpmove": "other:jump"}


class Exe:
    def __init__(self, path):
        self.pe = pefile.PE(path, fast_load=True)
        self.base = self.pe.OPTIONAL_HEADER.ImageBase
        self.img = self.pe.get_memory_mapped_image()
        self.md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
        self.mu = Uc(UC_ARCH_X86, UC_MODE_32)
        size = (self.pe.OPTIONAL_HEADER.SizeOfImage + 0xFFF) & ~0xFFF
        self.mu.mem_map(self.base, size)
        self.mu.mem_write(self.base, self.img[:size])
        self.mu.mem_map(0x20000000, 0x100000)

    def rd(self, a, n):
        return self.img[a - self.base:a - self.base + n]

    def u32(self, a):
        return struct.unpack("<I", self.rd(a, 4))[0]

    def cstr(self, a):
        out = bytearray()
        while True:
            c = self.rd(a, 1)[0]
            if not c:
                return out.decode("latin1")
            out.append(c)
            a += 1

    def call(self, fn, args=(), ecx=0):
        """Runs a leaf function of the exe and returns eax."""
        mu = self.mu
        esp = 0x20070000
        ret = 0x20000010
        stack = struct.pack("<I", ret) + b"".join(struct.pack("<I", a & 0xFFFFFFFF) for a in args)
        mu.mem_write(esp, stack)
        mu.reg_write(UC_X86_REG_ESP, esp)
        mu.reg_write(UC_X86_REG_ECX, ecx)
        mu.emu_start(fn, ret, count=20000)
        return mu.reg_read(UC_X86_REG_EAX)

    def route(self, skill, weapon_type):
        mu, md = self.mu, self.md
        ebp = 0x20080000
        mu.reg_write(UC_X86_REG_EBP, ebp)
        mu.reg_write(UC_X86_REG_ESP, ebp - 0x800)
        mu.reg_write(UC_X86_REG_ESI, skill)
        mu.mem_write(ebp - 0x18, struct.pack("<I", weapon_type))
        mu.mem_write(ebp - 0x10, struct.pack("<I", 0x20010000))
        mu.mem_write(0x20010000, struct.pack("<I", skill))
        out = {"r": None}

        def hook(uc, addr, size, _):
            if addr in (0x967B8B, 0x9693E3):
                out["r"] = "none"
                uc.emu_stop()
                return
            ins = next(md.disasm(bytes(uc.mem_read(addr, size)), addr))
            if ins.mnemonic != "call":
                return
            try:
                target = int(ins.op_str, 16)
            except ValueError:
                out["r"] = "other:indirect"
                uc.emu_stop()
                return
            if target in (0x766157, 0x765E2D):  # weapon check passes; 0x765e2d (keydown-cancel ids) evaluated
                uc.reg_write(UC_X86_REG_EAX, 1 if target == 0x766157 else self.call(0x765E2D, (skill,)))
                uc.reg_write(UC_X86_REG_EIP, addr + size)
                return
            out["r"] = HANDLERS.get(target, f"other:{target:#x}")
            uc.emu_stop()

        h = mu.hook_add(UC_HOOK_CODE, hook)
        try:
            mu.emu_start(0x9678E6, 0x969A60, count=5000)
        except Exception as e:  # noqa: BLE001 - recorded in the row
            out["r"] = out["r"] or f"other:err:{e}"
        mu.hook_del(h)
        return out["r"] or "none"

    def weapon_types(self, skill, wz_weapon):
        entry = 0x20020000
        self.mu.mem_write(entry, struct.pack("<I", skill) + b"\0" * 0x10 + struct.pack("<I", wz_weapon))
        return [wt for wt in range(30, 50) if self.call(0x766157, (wt,), ecx=entry)]

    def string_pool(self, idx):
        """StringPool::GetString (0x79e805): the encoded entry at ms_aString[idx] (0xbdc9d4), XORed with the
        16-byte key at 0xb001ec rotated left by the entry's first byte (the seed) in bits (0x79e780 / 0x79ebf3)."""
        ptr = self.u32(0xBDC9D4 + 4 * idx)
        seed = self.rd(ptr, 1)[0]
        raw = bytearray()
        a = ptr + 1
        while self.rd(a, 1)[0]:
            raw.append(self.rd(a, 1)[0])
            a += 1
        key = bytearray(self.rd(0xB001EC, 16))
        if seed >= 8:
            shift = (seed >> 3) % 16
            key = key[shift:] + key[:shift]
        bits = seed & 7
        if bits:
            v = int.from_bytes(key, "big")
            v = ((v << bits) | (v >> (128 - bits))) & ((1 << 128) - 1)
            key = bytearray(v.to_bytes(16, "big"))
        return bytes(c if c == key[i % 16] else c ^ key[i % 16] for i, c in enumerate(raw)).decode("latin1")

    def action_names(self):
        """The body-action table is filled at start-up (0x4a38e7): each entry gets a StringPool string."""
        names = {}
        last_push = None
        pending = None
        for ins in self.md.disasm(bytes(self.rd(0x4A38E7, 0x4000)), 0x4A38E7):
            if ins.mnemonic == "push" and ins.op_str.startswith("0x"):
                last_push = int(ins.op_str, 16)
            elif ins.mnemonic == "call" and ins.op_str == "0x79e805":
                pending = self.string_pool(last_push)
            elif ins.mnemonic == "mov" and ins.op_str.startswith("ecx, 0x") and pending is not None:
                entry = int(ins.op_str[5:], 16)
                if ACTION_TABLE <= entry < ACTION_TABLE + 24 * ACTION_COUNT and (entry - ACTION_TABLE) % 24 == 0:
                    names[(entry - ACTION_TABLE) // 24] = pending
                    pending = None
            elif ins.mnemonic == "ret":
                break
        return names

    def has_imm(self, lo, hi, value):
        return struct.pack("<I", value) in bytes(self.rd(lo, hi - lo + 8))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--exe", required=True)
    ap.add_argument("--skills", required=True, help="client-attack-data.tsv (client_attack_fixture.py)")
    ap.add_argument("--mapleroot-routes", required=True, help="the client DLL's SkillEdits/Activeskill.h")
    a = ap.parse_args()
    exe = Exe(a.exe)
    with open(a.exe, "rb") as f:
        md5 = hashlib.md5(f.read()).hexdigest()
    skills = {}
    with open(a.skills) as f:
        for line in f:
            if line.startswith("S\t"):
                p = line.rstrip("\n").split("\t")
                skills[int(p[1])] = int(p[3])
    custom = {}
    text = open(a.mapleroot_routes).read()
    body = text[text.index("doActiveSkills()"):]
    body = body[:body.index("jmp[doActiveJmpBack]")]
    cur = None
    for line in body.split("\n"):
        m = re.search(r"mov eax,\s*(\d+)", line)
        if m:
            cur = int(m.group(1))
        m = re.search(r"je\s*\[(\w+)\]", line)
        if m and cur is not None:
            custom.setdefault(cur, MAPLEROOT_ROUTES.get(m.group(1), "other:" + m.group(1)))
            cur = None
    for name, (lo, hi, ids) in ON_ATTACK_LISTS.items():
        for i in ids:
            if not exe.has_imm(lo, hi, i):
                sys.exit(f"{name}: {i} not found at {lo:#x}..{hi:#x} - not the exe these rules were read from")
    out = sys.stdout
    out.write(f"# MapleStory.exe md5 {md5} (tools/bot-attack-rules/exe_rules.py)\n")
    names = exe.action_names()
    if len(names) != ACTION_COUNT:
        sys.exit(f"read {len(names)} of {ACTION_COUNT} action names from 0x4a38e7")
    for code in range(ACTION_COUNT):
        out.write(f"A\t{code}\t{names[code]}\n")
    for name, base, stride, n in TABLES:
        for idx in range(18):
            row = base + idx * stride
            cnt = exe.u32(row)
            codes = [exe.u32(row + 4 + 4 * i) for i in range(min(cnt, n))]
            out.write(f"T\t{name}\t{idx // 2 + 1}\t{'type3' if idx % 2 else 'norm'}\t{','.join(map(str, codes))}\n")
    for skill in sorted(skills):
        flags = []
        if exe.call(0x4FB08F, (skill,)):
            flags.append("keydown")
        for name, (_, _, ids) in ON_ATTACK_LISTS.items():
            if skill in ids:
                flags.append(name)
        if exe.call(0x76681D, (skill,)):
            flags.append("shootWithoutWeapon")
        if exe.call(0x7668B7, (skill,)):
            flags.append("shootNoBullet")
        wts = exe.weapon_types(skill, skills[skill])
        route = custom.get(skill)
        if route is None:
            # a branch may check the weapon type ([ebp-0x18]) and print a message when it does not fit: try the
            # weapon types the skill takes until one reaches a handler
            tried = []
            for wt in (wts if len(wts) < 20 else [30, 40, 43, 44, 33, 37, 38, 45, 46, 47, 48, 49]) + [0]:
                r = exe.route(skill, wt)
                tried.append(r)
                if r != "other:0x79e805" and not r.startswith("other:err"):
                    break
            route = r if r != "other:0x79e805" else "gated"
        out.write(f"K\t{skill}\t{','.join(flags)}\t{route}\t{','.join(map(str, wts))}\n")


if __name__ == "__main__":
    main()
