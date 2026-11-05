/* Read-only normal equipment route. Enable with the matching loot migration and jar. */
class NormalGearGuideInteraction {
    constructor(cm) {
        this.cm=cm;
        this.data=getJson('normalGearGuide.json');
        this.family=null;
        this.band=null;
    }
    action(mode,type,selection,status) {
        if (mode!==1 || !Java.type('soloMapling.crafting.NormalGearBridge').isEnabled())
            return ScriptInteractionResult.Dispose;
        if (status===1) {
            let text='Normal equipment can take you through level 200 before rebirth crafting.\r\nChoose a class family:\r\n';
            this.data.families.forEach((f,i)=>text+=`#L${i}#${f.name}#l\r\n`);
            this.cm.sendSimple(text);
            return status;
        }
        if (status===2) {
            if (!Number.isInteger(selection) || !this.data.families[selection]) return ScriptInteractionResult.Dispose;
            this.family=this.data.families[selection];
            let text=`${this.family.name} equipment\r\nChoose a stage. You can collect the next set before you can wear it.\r\n`;
            this.family.bands.forEach((b,i)=>text+=`#L${i}#${b.name}: hunt from ${b.start}, equip the set at ${b.equip}#l\r\n`);
            this.cm.sendSimple(text);
            return status;
        }
        if (status===3 && this.family) {
            if (!Number.isInteger(selection) || !this.family.bands[selection]) return ScriptInteractionResult.Dispose;
            this.band=this.family.bands[selection];
            let text=`${this.band.name}\r\nChoose an item to see its sources:\r\n`;
            this.band.items.forEach(i=>text+=`#L${i.id}##i${i.id}# #t${i.id}# (equip ${i.equip})#l\r\n`);
            this.cm.sendSimple(text);
            return status;
        }
        if (status===4 && this.band) {
            const item=this.band.items.find(i=>i.id===selection);
            if (!item) return ScriptInteractionResult.Dispose;
            let text=`#i${item.id}# #t${item.id}#\r\nEquip level: ${item.equip}\r\n`;
            text+='Your class and stat requirements still apply.\r\n\r\nHunting sources:\r\n';
            item.sources.forEach(s=>text+=`#o${s.mob}# in #m${s.map}#\r\n`);
            text+='\r\nStart with gear you can already wear. If a fight is too costly, stay on an easier route while upgrading.';
            text+='\r\nAt 150, look for Commerci; at 160, Sweetwater. Save forged sets for rebirth progression.';
            text+='\r\nChoose enhancements carefully: scrolls and Spell Trace enhancements share upgrade slots.';
            this.cm.sendOk(text);
            return ScriptInteractionResult.Dispose;
        }
        return ScriptInteractionResult.Dispose;
    }
}
