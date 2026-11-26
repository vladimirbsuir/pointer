import json
import os
import random
import sys

WORDS = [
    "Apply", "Archive", "Back", "Bookmark", "Cancel", "Close", "Copy", "Delete", "Details", "Download",
    "Edit", "Export", "Filter", "Forward", "Help", "History", "Import", "Insert", "Login", "Logout",
    "Merge", "Move", "Next", "Open", "Pause", "Play", "Previous", "Print", "Publish", "Refresh",
    "Rename", "Reset", "Restore", "Save", "Search", "Settings", "Share", "Sort", "Start", "Stop",
    "Submit", "Sync", "Undo", "Unlock", "Update", "Upload", "Verify", "Zoom",
]
FILLS = ["#e8eaf0", "#dfe6f2", "#ecebe4", "#e4ecea"]
MARGIN = 12
FONT = ("DejaVu Sans", 11)
DENSE_FONT = ("DejaVu Sans", 7)
SIZES = {False: ((70, 120), (24, 34)), True: ((36, 64), (14, 20))}


def layout(width, height, seed, dense=False):
    generator = random.Random(seed)
    (min_width, max_width), (min_height, max_height) = SIZES[dense]
    targets = []
    for label in WORDS:
        for _ in range(500):
            target_width = generator.randint(min_width, max_width)
            target_height = generator.randint(min_height, max_height)
            x = generator.randint(MARGIN, width - target_width - MARGIN)
            y = generator.randint(MARGIN, height - target_height - MARGIN)
            box = {"label": label, "x": x, "y": y, "width": target_width, "height": target_height}
            if all(apart(box, other) for other in targets):
                targets.append(box)
                break
    return targets


def apart(first, second):
    return (first["x"] + first["width"] + MARGIN <= second["x"]
            or second["x"] + second["width"] + MARGIN <= first["x"]
            or first["y"] + first["height"] + MARGIN <= second["y"]
            or second["y"] + second["height"] + MARGIN <= first["y"])


def picks(seed, count):
    return random.Random(f"picks-{seed}").sample(WORDS, count)


def hit(targets, x, y):
    for target in targets:
        if target["x"] <= x < target["x"] + target["width"] and target["y"] <= y < target["y"] + target["height"]:
            return target["label"]
    return None


def main():
    import tkinter as tk

    arguments = [argument for argument in sys.argv[1:] if not argument.startswith("--")]
    check_path = arguments[0] if arguments else "/tmp/grid-targets.json"
    seed = next((int(argument.split("=", 1)[1]) for argument in sys.argv[1:] if argument.startswith("--seed=")), 1)
    dense = "--dense" in sys.argv[1:]
    root = tk.Tk()
    root.title("Grid Targets")
    width, height = root.winfo_screenwidth(), root.winfo_screenheight()
    root.geometry(f"{width}x{height}+0+0")
    root.attributes("-fullscreen", True)
    canvas = tk.Canvas(root, width=width, height=height, background="#ffffff", highlightthickness=0)
    canvas.pack()
    targets = layout(width, height, seed, dense)
    rectangles = {}
    for index, target in enumerate(targets):
        rectangles[target["label"]] = canvas.create_rectangle(
            target["x"], target["y"], target["x"] + target["width"] - 1, target["y"] + target["height"] - 1,
            fill=FILLS[index % len(FILLS)], outline="#8a8f99")
        canvas.create_text(target["x"] + target["width"] / 2, target["y"] + target["height"] / 2,
                           text=target["label"], font=DENSE_FONT if dense else FONT, fill="#202124")
    state = {"screen": [width, height], "seed": seed, "dense": dense, "targets": targets, "clicks": []}

    def write_state():
        temporary = check_path + ".tmp"
        with open(temporary, "w", encoding="utf-8") as output:
            json.dump(state, output)
        os.replace(temporary, check_path)

    def clicked(event):
        label = hit(targets, event.x_root, event.y_root)
        state["clicks"].append({"x": event.x_root, "y": event.y_root, "label": label})
        if label:
            canvas.itemconfigure(rectangles[label], fill="#b9e6c4")
        write_state()

    def ready():
        if not canvas.winfo_ismapped():
            root.after(100, ready)
            return
        write_state()
        print("grid-targets ready", flush=True)

    canvas.bind("<Button-1>", clicked)
    root.after(200, ready)
    root.mainloop()


if __name__ == "__main__":
    main()
