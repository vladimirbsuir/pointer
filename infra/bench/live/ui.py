import json
import sys
from pathlib import Path

from cdp import evaluate

HELPERS = """
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const press = (element) => {
  element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, pointerType: 'mouse' }));
  element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
  element.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, pointerType: 'mouse' }));
  element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0 }));
  element.click();
};
const byText = (text) => [...document.querySelectorAll('button, a, [role=menuitem], [role=button], li, div')]
  .filter((element) => element.innerText && element.innerText.trim() === text)
  .sort((a, b) => a.innerText.length - b.innerText.length || b.querySelectorAll('*').length - a.querySelectorAll('*').length)
  .pop();
"""


def click(text):
    return evaluate("(async () => {" + HELPERS + "const element = byText(" + json.dumps(text) + ");"
                    " if (!element) return 'not found'; press(element); await sleep(1500);"
                    " return document.body.innerText.slice(0, 600); })()")


def send(message):
    return evaluate("(async () => {" + HELPERS + """
      const box = document.querySelector('textarea');
      if (!box) return 'no composer';
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
      setter.call(box, """ + json.dumps(message) + """);
      box.dispatchEvent(new Event('input', { bubbles: true }));
      await sleep(200);
      box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
      await sleep(1000);
      return 'sent';
    })()""")


def text(limit=3000):
    return evaluate(f"document.querySelector('main') ? document.querySelector('main').innerText.slice(-{limit}) : document.body.innerText.slice(-{limit})")


if __name__ == "__main__":
    action = sys.argv[1]
    if action == "click":
        print(click(sys.argv[2]))
    elif action == "send":
        print(send(sys.argv[2]))
    elif action == "signout":
        print(evaluate((Path(__file__).parent / "signout.js").read_text(encoding="utf-8")))
    elif action == "text":
        print(text(int(sys.argv[2]) if len(sys.argv) > 2 else 3000))
