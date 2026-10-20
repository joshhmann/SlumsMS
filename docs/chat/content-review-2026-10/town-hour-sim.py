# One-hour Henesys transcript from the reviewed packs, using the live code's timings:
# SocialHotPotatoManager (bark tick 8-20s: 45% chat, 15% emote+chat, 8% chalkboard; categories uniform),
# ConversationManager (a scripted conversation every 20-40s after the last one, lines ~5s apart),
# BotChatter TownChatter exchanges (cooldown 30-90s, turns 2.2-3.8s apart),
# megaphone ticker (tick 120-360s, gate 120s, category weights as shipped).
import yaml,random,sys,collections
D="/root/work/SlumsMS-t47/src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/"
seed=int(sys.argv[1]) if len(sys.argv)>1 else 47
R=random.Random(seed)
hp=yaml.safe_load(open(D+"SocialHotPotatoDialogue.yaml"))["SocialHotPotato"]
cats=["RealLife","MapleInUniverse","SocialSim","AFK","Nostalgia","RandomOneLiners","Complaints","Reactions","FlexBrag"]
mega=yaml.safe_load(open(D+"MegaphoneDialogue.yaml"))["MegaphoneBroadcast"]
mw={"BirthdayMessages":5,"ItemSales":12,"GuildRecruitment":10,"PQRecruitment":7,"RWTSpam":3,"Flex":19,"RandomAnnouncements":22,"SocialMessages":22}
conv=yaml.safe_load(open(D+"ConversationDialogue.yaml"))["conversations"]
tc=yaml.safe_load(open(D+"TownChatterDialogue.yaml"))["exchanges"]
names="Tiger ResMe xXDarkHeroXx Pinkiepie NightLordz CherryBlossom Kyuubi92 Snowfox lilbishop Mapler4Life Dawnly Ilbikid Takoyaki Sk8erboi Ruffles zKnight OrbisGirl Hermitz Slimey Pancakez".split()
ev=[]
def line(t,who,s,kind): ev.append((t,who,s,kind))
def txt(n): return [x if isinstance(x,str) else (x.get('line') or x.get('text')) for x in n['text']]
t=0
while t<3600:
    t+=R.uniform(8,20); r=R.randrange(100)
    if r<45 or 77<=r<92:
        line(t,R.choice(names),R.choice(txt(hp[R.choice(cats)])),"bark")
    elif r>=92:
        line(t,R.choice(names),"[chalkboard] "+R.choice(txt(hp["Chalkboard"])),"chalk")
t=R.uniform(0,30)
keys=list(conv)
while t<3600:
    c=conv[R.choice(keys)]; who={}
    pool=R.sample(names,4)
    for l in c['lines']:
        sp=l["speaker"]
        if sp not in who: who[sp]=pool[len(who)]
        line(t,who[sp],l['text'],"conv"); t+=R.uniform(4,6)
    t+=R.uniform(20,40)
t=R.uniform(0,60); deck=[]
while t<3600:
    if not deck: deck=tc[:]; R.shuffle(deck)
    ex=deck.pop(); a,b=R.sample(names,2)
    for i,s in enumerate(ex):
        line(t,(a,b)[i%2],s.replace("{MAP}","Henesys"),"chatter"); t+=R.uniform(2.2,3.8)
    t+=R.uniform(30,90)
t=0; last=-1e9
while t<3600:
    t+=R.uniform(120,360)
    if t-last<120: continue
    k=R.choices(list(mw),weights=list(mw.values()))[0]
    line(t,R.choice(names),"[MEGAPHONE] "+R.choice(txt(mega[k])),"mega:"+k); last=t
ev.sort()
for t,w,s,k in ev:
    if t<3600: print("%02d:%02d  %-14s %s"%(t//60,t%60,w+":",s))
lines=[e for e in ev if e[0]<3600]
kinds=collections.Counter(e[3].split(':')[0] for e in lines)
dup=sum(c-1 for c in collections.Counter(e[2] for e in lines).values() if c>1)
print("\n# %d lines in 60 min (%.1f/min): %s; repeated lines: %d; megaphones: %s"%(len(lines),len(lines)/60,dict(kinds),dup,[e[3][5:] for e in lines if e[3].startswith('mega')]),file=sys.stderr)
