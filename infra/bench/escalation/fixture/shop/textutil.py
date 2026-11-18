import re


def slugify(text):
    lowered = text.strip().lower()
    return re.sub(r"[^a-z0-9]", "-", lowered)


def truncate(text, limit):
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "\u2026"
