class WeaponCoreExchangeInteraction {
    constructor(cm) {
        this.cm=cm;
        this.service=Java.type('soloMapling.crafting.WeaponCoreExchange');
        this.cores=[4036544,4036545,4036546,4036547,4036548];
        this.source=null;
        this.target=null;
    }
    action(mode,type,selection,status) {
        if (mode !== 1) return ScriptInteractionResult.Dispose;
        if (status === 1) {
            let text='Exchange one class weapon core for another. The exchange is one for one, with no meso fee.\r\n\r\nChoose the core to give me:\r\n';
            for (const id of this.cores) text+=`#L${id}##i${id}# #t${id}# (${this.cm.getItemQuantity(id)})#l\r\n`;
            this.cm.sendSimple(text);
            return status;
        }
        if (status === 2) {
            if (!this.cores.includes(selection)) return ScriptInteractionResult.Dispose;
            this.source=selection;
            let text='Choose the core you want:\r\n';
            for (const id of this.cores.filter(id=>id!==this.source)) text+=`#L${id}##i${id}# #t${id}##l\r\n`;
            this.cm.sendSimple(text);
            return status;
        }
        if (status === 3) {
            if (!this.cores.includes(selection)||selection===this.source) return ScriptInteractionResult.Dispose;
            this.target=selection;
            this.cm.sendYesNo(`Give one #i${this.source}# #t${this.source}# for one #i${this.target}# #t${this.target}#?\r\nNo meso fee.`);
            return status;
        }
        if (status === 4 && this.source && this.target) {
            this.cm.sendOk(this.service.exchange(this.cm,this.source,this.target).text());
            return ScriptInteractionResult.Dispose;
        }
        return ScriptInteractionResult.Dispose;
    }
}
