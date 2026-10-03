// Installed by Curfew. Enabled only in standard (child) accounts, through the dconf profile set up
// by debian/postinst. Airplane mode cuts the Wi-Fi, and with it the parent's phone, so its button
// in the quick settings is hidden here. (Curfew also turns the Wi-Fi back on if it goes off
// another way, and tells the parent.)
import GLib from 'gi://GLib';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

export default class CurfewExtension extends Extension {
    enable() {
        this._hidden = [];
        // The quick settings build their buttons a moment after the shell starts.
        this._wait = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 250, () => {
            const rfkill = Main.panel.statusArea.quickSettings?._rfkill;
            if (!rfkill)
                return GLib.SOURCE_CONTINUE;
            for (const item of rfkill.quickSettingsItems ?? []) {
                item.visible = false;
                // the button shows itself again whenever the system says airplane mode is there
                const id = item.connect('notify::visible', () => {
                    if (item.visible)
                        item.visible = false;
                });
                this._hidden.push([item, id]);
            }
            this._wait = 0;
            return GLib.SOURCE_REMOVE;
        });
    }

    disable() {
        if (this._wait)
            GLib.source_remove(this._wait);
        this._wait = 0;
        for (const [item, id] of this._hidden) {
            item.disconnect(id);
            item._manager?.notify('show-airplane-mode');    // shown again if it should be
        }
        this._hidden = [];
    }
}
