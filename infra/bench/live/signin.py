import json
from pathlib import Path

from cdp import evaluate

account = json.loads((Path(__file__).parent / "account.local.json").read_text())
script = """
(async () => {
  const setValue = (element, value) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  };
  const email = document.querySelector('input[type=email]');
  const password = document.querySelector('input[type=password]');
  if (!email || !password) return 'no sign-in form: ' + document.body.innerText.slice(0, 200);
  setValue(email, EMAIL);
  setValue(password, PASSWORD);
  password.form.requestSubmit();
  await new Promise(resolve => setTimeout(resolve, 4000));
  return document.body.innerText.slice(0, 400);
})()
""".replace("EMAIL", json.dumps(account["email"])).replace("PASSWORD", json.dumps(account["password"]))
print(evaluate(script))
