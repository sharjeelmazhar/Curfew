// Test only: reports whether the airplane-mode button would be on screen, and saves a picture
// of the open quick settings.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Shell from 'gi://Shell';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';

export default class Probe extends Extension {
    enable() {
        GLib.timeout_add(GLib.PRIORITY_DEFAULT, 4000, () => {
            const qs = Main.panel.statusArea.quickSettings;
            const items = qs._rfkill?.quickSettingsItems ?? [];
            const bt = qs._bluetooth?.quickSettingsItems ?? [];
            print('PROBE ' + JSON.stringify({
                airplane: items.map(i => i.visible), offered: items.map(i => i._manager?.show_airplane_mode),
                bluetooth: bt.map(i => i.visible), title: items.map(i => i.title),
            }));
            qs.menu.open();
            GLib.timeout_add(GLib.PRIORITY_DEFAULT, 1500, () => {
                const shot = new Shell.Screenshot();
                const file = GLib.getenv('PROBE_SHOT');
                const stream = Gio.File.new_for_path(file).replace(null, false, 0, null);
                shot.screenshot(false, stream, (o, res) => {
                    try { o.screenshot_finish(res); } catch (e) { print('PROBE shot failed ' + e); }
                    stream.close(null);
                    print('PROBE done');
                });
                return GLib.SOURCE_REMOVE;
            });
            return GLib.SOURCE_REMOVE;
        });
    }
    disable() {}
}
