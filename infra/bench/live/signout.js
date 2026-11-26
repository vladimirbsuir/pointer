(async () => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const press = (element) => {
    element.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, pointerType: 'mouse' }));
    element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }));
    element.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, button: 0, pointerType: 'mouse' }));
    element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, button: 0 }));
    element.click();
  };
  const buttons = [...document.querySelectorAll('button')];
  const account = buttons.reverse().find((button) => button.querySelector('[data-slot=avatar], span') && button.closest('aside, nav, div'));
  const trigger = [...document.querySelectorAll('button')].filter((b) => b.getAttribute('aria-haspopup') === 'menu').pop();
  press(trigger || account);
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
