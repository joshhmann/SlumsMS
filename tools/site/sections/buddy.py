"""Slums-chan on every page, with a speech bubble about the page (owner, 2026-10-27: "at least one Slums-chan per page
with a bubble corresponding with the topic"). The home page has its own pictures (the hero) and the 404 page its
sleeping one, so neither gets one.

One slot per kind of page: (picture, line). The picture is a name in docs/site/assets/brand/ without the size and
extension (each comes as -360.webp and -560.webp, 360 and 560 px wide). The owner is drawing a picture for each topic:
to use a new one, add its two files and change the name in its slot. A slot with several lines picks one per page, so
neighbouring pages don't all say the same thing. With the Slums-chan theme on, theme.js swaps the picture for one of
the player's cards.
"""
import json
import re
import zlib

from common import esc

# picture name -> (width, height) of its 360-wide file, for the img size hints
PICTURES = {
    "chan-reach": (360, 384), "chan-megaphone": (360, 376), "chan-book": (360, 430), "chan-pout": (360, 386),
    "chan-lookback": (360, 390), "chan-money": (360, 360), "chan-clipboard": (360, 415), "chan-shrug": (360, 330),
    "chan-boba": (360, 393), "chan-point": (360, 389), "chan-chin": (360, 394), "chan-sleep": (360, 261),
    "chan-envelope": (360, 469), "chan-peace": (360, 393),
    # the chibi "server life" stickers (owner's sheet, 2026-11-04): one per topic where a general picture stood in
    "chibi-mushroom": (360, 362), "chibi-map": (360, 344), "chibi-sack": (360, 336), "chibi-leaf": (360, 342),
    "chibi-computer": (360, 297), "chibi-check": (360, 337), "chibi-megaphone": (360, 308), "chibi-laptop": (360, 346),
    "chibi-server": (360, 298), "chibi-box": (360, 346), "chibi-wrench": (360, 352), "chibi-magnifier": (360, 310),
    "chibi-facepalm": (360, 257), "chibi-gamer": (360, 308), "chibi-cheer": (360, 265), "chibi-shock": (360, 325),
    "chibi-cool": (360, 314), "chibi-levelup": (360, 273),
}
ALT = {
    "chan-reach": "Slums-chan winks and reaches out to you",
    "chan-megaphone": "Slums-chan shouts into a megaphone",
    "chan-book": "Slums-chan reads a book, a light bulb over her head",
    "chan-pout": "Slums-chan pouts with her chin in her hands",
    "chan-lookback": "Slums-chan looks back over her shoulder, a heart beside her",
    "chan-money": "Slums-chan hugs a bag of mesos and a pile of coins",
    "chan-clipboard": "Slums-chan writes on a clipboard with a quill",
    "chan-shrug": "Slums-chan shrugs, a question mark over her head",
    "chan-boba": "Slums-chan sips a bubble tea and winks",
    "chan-point": "Slums-chan points right at you",
    "chan-chin": "Slums-chan rests her chin in her hand with a little smile",
    "chan-sleep": "Slums-chan naps on her arms",
    "chan-envelope": "Slums-chan holds up a letter with a big smile",
    "chan-peace": "Slums-chan throws two peace signs",
    "chibi-mushroom": "Little Slums-chan hugs an Orange Mushroom",
    "chibi-map": "Little Slums-chan reads a map",
    "chibi-sack": "Little Slums-chan hauls a sack of gold coins",
    "chibi-leaf": "Little Slums-chan holds up a shining maple leaf",
    "chibi-computer": "Little Slums-chan at an old computer with a maple leaf on the screen",
    "chibi-check": "Little Slums-chan holds a checklist with a tick",
    "chibi-megaphone": "Little Slums-chan shouts into a megaphone",
    "chibi-laptop": "Little Slums-chan types on a laptop",
    "chibi-server": "Little Slums-chan fixes a server with a wrench",
    "chibi-box": "Little Slums-chan peeks over a box of goodies",
    "chibi-wrench": "Little Slums-chan in a hard hat with a wrench",
    "chibi-magnifier": "Little Slums-chan looks through a magnifying glass",
    "chibi-facepalm": "Little Slums-chan facepalms",
    "chibi-gamer": "Little Slums-chan plays in cat-ear headphones",
    "chibi-cheer": "Little Slums-chan cheers with a fist in the air",
    "chibi-shock": "Little Slums-chan stares at a tablet, shocked",
    "chibi-cool": "Little Slums-chan in pixel sunglasses",
    "chibi-levelup": "Little Slums-chan cheers beside a level-up arrow",
}

SLOTS = {
    # News
    "news": ("chan-megaphone", "Fresh patch notes! Read these before you ask me."),
    "release": ("chan-megaphone", ["What's new? Let me show you.", "Patch day! My favourite.", "Read it all. There's a quiz."]),
    # Guides: one line per page
    "guide": ("chan-book", "Bookmark this one."),
    "guide/index.html": ("chan-book", "New here? Start with Getting started!"),
    "guide/getting-started.html": ("chan-book", "Five minutes and you're in. Promise."),
    "guide/rates.html": ("chibi-cool", "The rates are... generous."),
    "guide/hubs.html": ("chibi-mushroom", "Meet me at the FM!"),
    "guide/getting-around.html": ("chibi-map", "This way! No, the other way."),
    "guide/travel.html": ("chibi-map", "Pack light. We're going places."),
    "guide/jobs.html": ("chan-book", "Pick a job, any job."),
    "guide/leveling.html": ("chibi-levelup", "Level up with me!"),
    "guide/rebirths.html": ("chibi-levelup", "Again! Again!"),
    "guide/money.html": ("chibi-sack", "Mesos don't grow on trees. Mostly."),
    "guide/shops.html": ("chibi-box", "Shopping spree time!"),
    "guide/regulars.html": ("chan-book", "Say hi to the regulars for me."),
    "guide/commands.html": ("chibi-computer", "Psst. These save a lot of clicking."),
    "guide/faq.html": ("chibi-magnifier", "Ask me anything!"),
    "guide/guidebook.html": ("chan-book", "The whole guidebook, right here."),
    # Slums Street
    "street": ("chibi-magnifier", "Looking for something? This street has it all."),
    "street-list": ("chan-pout", ["So many to pick from!", "Take your time. I'll wait.", "Ooh, look at all these."]),
    "item": ("chibi-leaf", ["Want one? Check who drops it.", "Ooh, shiny.", "Put it on your wish list!",
                              "Check the FM price before you farm it."]),
    "mob": ("chibi-mushroom", ["Careful, this one bites.", "Bring potions. Trust me.", "Hunt it for the drops!",
                             "Watch out! It's got friends."]),
    "map": ("chibi-map", ["Ooh, where's this? Take me with you.", "See who hangs out here.", "Nice view, huh?"]),
    "npc": ("chan-envelope", ["Say hi for me!", "They've got stuff to say. Listen!", "Be nice. They remember."]),
    "quest": ("chibi-check", ["Quest time! Bring snacks.", "Read the steps before you go.", "Easy. Probably."]),
    "changes": ("chibi-server", "We changed these on purpose. Promise."),
    "whacky": ("chibi-shock", "A mage with a sword? I love it."),
    # Slums Market
    "market": ("chan-money", "Buy low, sell high!"),
    "market/sales": ("chibi-sack", "Who's spending all those mesos?"),
    "market/listings": ("chibi-box", "Window shopping in the FM!"),
    "market/board": ("chibi-megaphone", "Post it. Someone might bite!"),
    "market/prices": ("chan-money", ["Fair price? Let's check.", "Is it worth it? Check the chart!",
                                         "Don't overpay. I'm watching."]),
    "market/nx": ("chibi-laptop", "Mesos for NX, NX for mesos."),
    "market/economy": ("chan-money", "Money makes Bera go round."),
    "market/exchange": ("chibi-computer", ["Not open yet. Soon!", "Saving my mesos for opening day.",
                                         "Nothing to trade yet. Just looking!"]),
    "market/invites": ("chan-envelope", ["Bring a friend!", "Henesys is full again!"]),
    "market/mine": ("chan-envelope", "All your stuff in one place."),
    "market/cards": ("chan-lookback", "Gotta find them all!"),
    "market/stamps": ("chibi-check", ["Did you check in today?", "Day 28 is the big one!"]),
    # World
    "world": ("chibi-gamer", "Who's on right now?"),
    "world/feed": ("chan-boba", "Ooh, gossip!"),
    "world/rankings": ("chibi-cheer", ["Who's on top? Not you... yet.", "Climb, climb, climb!"]),
    "world/economy": ("chan-money", "Money makes Bera go round."),
    # Sign-in and the rest
    "login": ("chan-lookback", "Welcome back! Bera missed you."),
    "join": ("chan-reach", "Come hang in Henesys! It's full again."),
    "forgot": ("chibi-facepalm", "Happens to the best of us."),
    "report": ("chibi-wrench", "Found a bug? Squash it!"),
}


# Lines that replace a page's own once its feature turns out to be on (exchange.js, when the exchange is open; owner,
# 2026-10-29, b067: neutral lines while it's closed, the trading ones when it opens)
OPEN_LINES = {
    "market/exchange": ["Buy low, sell high!", "Check the fee before you confirm.", "LEAF to the moon!"],
}

# After a page's own lines, the bubble also cycles through these (theme.js, every few seconds)
EVERYWHERE = ["Henesys is full again!", "Bustin' chat since 2006!", "See you in game!"]


def figure(root, slot, page=""):
    """The picture and its bubble. The bubble starts on one of the slot's lines (page picks which, the same every
    build), then theme.js circles through the slot's other lines and EVERYWHERE."""
    pic, lines = SLOTS[slot]
    lines = lines if isinstance(lines, list) else [lines]
    k = zlib.crc32(page.encode()) % len(lines)
    lines = lines[k:] + lines[:k] + EVERYWHERE
    line = lines[0]
    w, h = PICTURES[pic]
    src = f"{root}assets/brand/{pic}"
    opened = f' data-open-lines="{esc(json.dumps(OPEN_LINES[slot] + EVERYWHERE))}"' if slot in OPEN_LINES else ""
    return (f'<figure class="buddy"><p class="bubble" data-lines="{esc(json.dumps(lines))}"{opened}>{esc(line)}</p>'
            f'<img class="sidekick" src="{src}-360.webp" srcset="{src}-360.webp 360w, {src}-560.webp 560w" sizes="200px" '
            f'alt="{esc(ALT[pic])}" width="{w}" height="{h}" loading="lazy"></figure>')


def place(body, fig):
    """Before the page heading: a grid heading (.headfig) as a whole, else the first h1 (or at the top)."""
    for mark in ('<div class="headfig">', '<div class="notes-head', "<h1"):
        i = body.find(mark)
        if i >= 0:
            return body[:i] + fig + body[i:]
    return fig + body


def head(body, fig):
    """For the grid layouts (Slums Street, the market, World), where a float doesn't work: the heading (h1 and the line
    under it) and Slums-chan side by side in a .headfig row."""
    i = body.find('<div class="headfig"><div>')
    if i >= 0:  # already a heading row: she goes at its end
        j = body.index("</div>", i + 26) + 6
        return body[:j] + fig + body[j:]
    m = re.search(r'<h1[ >].*?</h1>(\s*<p class="?(?:id|lede)"?>.*?</p>)?', body, re.S)  # a line break may come between
    if not m:
        return fig + body
    return body[:m.start()] + f'<div class="headfig"><div>{m.group(0)}</div>{fig}</div>' + body[m.end():]
