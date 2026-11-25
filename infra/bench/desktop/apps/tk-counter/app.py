import json
import os
import sys
import tkinter as tk

CHECK_PATH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/tk-counter.json"
FONT = ("DejaVu Sans", 14)

state = {"count": 0, "saved": 0}

root = tk.Tk()
root.title("Tk Counter")
root.geometry("460x420+120+100")

label = tk.Label(root, text="Count: 0", font=FONT)
label.pack(pady=8)

entry = tk.Entry(root, font=FONT, width=30)
entry.pack(pady=8)

items = tk.Listbox(root, font=FONT, height=6)
for index in range(60):
    items.insert(tk.END, f"Item {index}")
items.pack(pady=8, fill=tk.X, padx=20)


def bounds(widget):
    return {
        "x": widget.winfo_rootx(),
        "y": widget.winfo_rooty(),
        "width": widget.winfo_width(),
        "height": widget.winfo_height(),
    }


def write_state(*_):
    snapshot = dict(state)
    snapshot["text"] = entry.get()
    snapshot["top"] = items.nearest(0)
    snapshot["button"] = bounds(button)
    snapshot["entry"] = bounds(entry)
    snapshot["list"] = bounds(items)
    temporary = CHECK_PATH + ".tmp"
    with open(temporary, "w", encoding="utf-8") as output:
        json.dump(snapshot, output, ensure_ascii=False)
    os.replace(temporary, CHECK_PATH)


def increment():
    state["count"] += 1
    label.configure(text=f"Count: {state['count']}")
    write_state()


def save(_event):
    state["saved"] += 1
    write_state()


button = tk.Button(root, text="Increment", font=FONT, command=increment)
button.pack(pady=8)

entry.bind("<KeyRelease>", write_state)
entry.bind("<<Paste>>", lambda _event: root.after(50, write_state))
items.bind("<MouseWheel>", lambda _event: root.after(50, write_state))
items.bind("<Button-4>", lambda _event: root.after(50, write_state))
items.bind("<Button-5>", lambda _event: root.after(50, write_state))
root.bind("<Control-s>", save)


def ready():
    if not button.winfo_ismapped() or button.winfo_width() <= 1:
        root.after(100, ready)
        return
    write_state()
    print("tk-counter ready", flush=True)


root.bind("<Configure>", lambda _event: root.after(50, write_state))
root.after(100, ready)
root.mainloop()
