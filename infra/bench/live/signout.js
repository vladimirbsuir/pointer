(async () => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const press = (element) => {
    element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, pointerType: 'mouse' }));
    element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
    element.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, pointerType: 'mouse' }));
    element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0 }));
    element.click();
  };
  const trigger = [...document.querySelectorAll('button[aria-haspopup=menu]')]
    .find((button) => button.querySelector('[data-slot=avatar]'));
  if (!trigger) return { error: 'account menu not found' };
  press(trigger);
  await sleep(500);
  const items = [...document.querySelectorAll('[role=menuitem]')];
  const labels = items.map((item) => item.innerText);
  const signOut = items.find((item) => /выйти|sign out/i.test(item.innerText));
  if (!signOut) return { labels };
  const started = Date.now();
  window.__signOutAt = started;
  press(signOut);
  await sleep(3000);
  return { labels, clickedAt: new Date(started).toISOString(), text: document.body.innerText.slice(0, 200) };
})()
