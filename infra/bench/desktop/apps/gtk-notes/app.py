import json
import os
import sys

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk

CHECK_PATH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/gtk-notes.json"

state = {"notes": [], "pinned": False, "exported": 0, "dialog": False}


def write_state():
    snapshot = dict(state)
    snapshot["draft"] = entry.get_text()
    temporary = CHECK_PATH + ".tmp"
    with open(temporary, "w", encoding="utf-8") as output:
        json.dump(snapshot, output, ensure_ascii=False)
    os.replace(temporary, CHECK_PATH)


def add_note(_button):
    text = entry.get_text().strip()
    if not text:
        return
    state["notes"].append(text)
    row = Gtk.ListBoxRow()
    row.add(Gtk.Label(label=text, xalign=0))
    row.show_all()
    notes.add(row)
    entry.set_text("")
    write_state()


def toggle_pinned(button):
    state["pinned"] = button.get_active()
    write_state()


def clear_draft(_item):
    entry.set_text("")
    write_state()


def export(_item):
    dialog = Gtk.FileChooserDialog(title="Export notes", parent=window, action=Gtk.FileChooserAction.SAVE)
    dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Export", Gtk.ResponseType.ACCEPT)
    dialog.set_current_name("notes.txt")
    dialog.set_do_overwrite_confirmation(True)
    state["dialog"] = True
    write_state()
    response = dialog.run()
    if response == Gtk.ResponseType.ACCEPT:
        path = dialog.get_filename()
        text = "".join(note + "\n" for note in state["notes"])
        with open(path, "w", encoding="utf-8") as output:
            output.write(text)
        state["exported"] += 1
        state["lastExport"] = {"path": path, "text": text}
    dialog.destroy()
    state["dialog"] = False
    write_state()


window = Gtk.Window(title="GTK Notes")
window.set_default_size(520, 420)
window.move(80, 60)
window.connect("destroy", Gtk.main_quit)

layout = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
menu_bar = Gtk.MenuBar()
file_item = Gtk.MenuItem(label="File")
file_menu = Gtk.Menu()
new_item = Gtk.MenuItem(label="New note")
new_item.connect("activate", clear_draft)
export_item = Gtk.MenuItem(label="Export…")
export_item.connect("activate", export)
file_menu.append(new_item)
file_menu.append(export_item)
file_item.set_submenu(file_menu)
menu_bar.append(file_item)
layout.pack_start(menu_bar, False, False, 0)

entry = Gtk.Entry()
entry.set_placeholder_text("Note text")
entry.get_accessible().set_name("Note text")
add_button = Gtk.Button(label="Add note")
add_button.connect("clicked", add_note)
pinned = Gtk.CheckButton(label="Pinned")
pinned.connect("toggled", toggle_pinned)
notes = Gtk.ListBox()
notes.get_accessible().set_name("Notes")

for widget in (entry, add_button, pinned):
    layout.pack_start(widget, False, False, 0)
layout.pack_start(notes, True, True, 0)
layout.set_border_width(12)
window.add(layout)
window.show_all()
write_state()
print("gtk-notes ready", flush=True)
Gtk.main()
