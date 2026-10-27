"""Slums-chan on every page, with a speech bubble about the page (owner, 2026-10-27: "at least one Slums-chan per page
with a bubble corresponding with the topic"). The home page has its own pictures (the hero) and the 404 page its
sleeping one, so neither gets one.

One slot per kind of page: (picture, line). The picture is a name in docs/site/assets/brand/ without the size and
extension (each comes as -360.webp and -560.webp, 360 and 560 px wide). The owner is drawing a picture for each topic:
to use a new one, add its two files and change the name in its slot. A slot with several lines picks one per page, so
neighbouring pages don't all say the same thing. With the Slums-chan theme on, theme.js swaps the picture for one of
the player's cards.
"""
import re
import zlib

from common import esc

# picture name -> (width, height) of its 360-wide file, for the img size hints
PICTURES = {
    "chan-reach": (360, 384), "chan-megaphone": (360, 376), "chan-book": (360, 430), "chan-pout": (360, 386),
    "chan-lookback": (360, 390), "chan-money": (360, 360), "chan-clipboard": (360, 415), "chan-shrug": (360, 330),
    "chan-boba": (360, 393), "chan-point": (360, 389), "chan-chin": (360, 394), "chan-sleep": (360, 261),
    "chan-envelope": (360, 469), "chan-peace": (360, 393),
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
}

SLOTS = {
    # News
    "news": ("chan-megaphone", "Fresh patch notes! Read these before you ask me."),
    "release": ("chan-megaphone", ["What's new? Let me show you.", "Patch day! My favourite.", "Read it all. There's a quiz."]),
    # Guides: one line per page
    "guide": ("chan-book", "Bookmark this one."),
    "guide/index.html": ("chan-book", "New here? Start with Getting started!"),
    "guide/getting-started.html": ("chan-book", "Five minutes and you're in. Promise."),
    "guide/rates.html": ("chan-book", "The rates are... generous."),
    "guide/hubs.html": ("chan-book", "Meet me at the FM!"),
    "guide/getting-around.html": ("chan-book", "This way! No, the other way."),
    "guide/travel.html": ("chan-book", "Pack light. We're going places."),
    "guide/jobs.html": ("chan-book", "Pick a job, any job."),
    "guide/leveling.html": ("chan-book", "Level up with me!"),
    "guide/rebirths.html": ("chan-book", "Again! Again!"),
    "guide/money.html": ("chan-book", "Mesos don't grow on trees. Mostly."),
    "guide/shops.html": ("chan-book", "Shopping spree time!"),
    "guide/regulars.html": ("chan-book", "Say hi to the regulars for me."),
    "guide/commands.html": ("chan-book", "Psst. These save a lot of clicking."),
    "guide/faq.html": ("chan-book", "Ask me anything!"),
    "guide/guidebook.html": ("chan-book", "The whole guidebook, right here."),
    # Slums Street
    "street": ("chan-shrug", "Looking for something? This street has it all."),
    "street-list": ("chan-pout", ["So many to pick from!", "Take your time. I'll wait.", "Ooh, look at all these."]),
    "item": ("chan-chin", ["Want one? Check who drops it.", "Ooh, shiny.", "Put it on your wish list!",
                              "Check the FM price before you farm it."]),
    "mob": ("chan-point", ["Careful, this one bites.", "Bring potions. Trust me.", "Hunt it for the drops!",
                             "Watch out! It's got friends."]),
    "map": ("chan-shrug", ["Ooh, where's this? Take me with you.", "See who hangs out here.", "Nice view, huh?"]),
    "npc": ("chan-envelope", ["Say hi for me!", "They've got stuff to say. Listen!", "Be nice. They remember."]),
    "quest": ("chan-clipboard", ["Quest time! Bring snacks.", "Read the steps before you go.", "Easy. Probably."]),
    "changes": ("chan-peace", "We changed these on purpose. Promise."),
    "whacky": ("chan-shrug", "A mage with a sword? I love it."),
    # Slums Market
    "market": ("chan-money", "Buy low, sell high!"),
    "market/sales": ("chan-money", "Who's spending all those mesos?"),
    "market/listings": ("chan-boba", "Window shopping in the FM!"),
    "market/board": ("chan-point", "Post it. Someone might bite!"),
    "market/prices": ("chan-money", ["Fair price? Let's check.", "Is it worth it? Check the chart!",
                                         "Don't overpay. I'm watching."]),
    "market/nx": ("chan-money", "Mesos for NX, NX for mesos."),
    "market/economy": ("chan-money", "Money makes Bera go round."),
    "market/mine": ("chan-envelope", "All your stuff in one place."),
    "market/cards": ("chan-lookback", "Gotta find them all!"),
    # World
    "world": ("chan-peace", "Who's on right now?"),
    "world/feed": ("chan-boba", "Ooh, gossip!"),
    "world/rankings": ("chan-point", ["Who's on top? Not you... yet.", "Climb, climb, climb!"]),
    "world/economy": ("chan-money", "Money makes Bera go round."),
    # Sign-in and the rest
    "login": ("chan-lookback", "Welcome back! Bera missed you."),
    "join": ("chan-reach", "Come hang in Henesys! It's full again."),
    "forgot": ("chan-pout", "Happens to the best of us."),
    "report": ("chan-clipboard", "Found a bug? Squash it!"),
}


def figure(root, slot, page=""):
    """The picture and its bubble. page picks between a slot's lines (the same line every build)."""
    pic, line = SLOTS[slot]
    if isinstance(line, list):
        line = line[zlib.crc32(page.encode()) % len(line)]
    w, h = PICTURES[pic]
    src = f"{root}assets/brand/{pic}"
    return (f'<figure class="buddy"><p class="bubble">{esc(line)}</p>'
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
    m = re.search(r'<h1[ >].*?</h1>(<p class="?(?:id|lede)"?>.*?</p>)?', body, re.S)
    if not m:
        return fig + body
    return body[:m.start()] + f'<div class="headfig"><div>{m.group(0)}</div>{fig}</div>' + body[m.end():]
