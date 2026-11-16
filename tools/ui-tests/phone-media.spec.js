const { test, expect } = require('@playwright/test');

// "Phone now playing": the phone's own media session through the native bridge (PhoneMedia.kt), faked here.
async function open(page, state) {
  await page.addInitScript((state) => {
    window.__phoneboxKey = 'pb-key-test';
    window.__pm = { state, calls: [] };
    const mine = (key) => key === 'pb-key-test';
    window.Android = {
      battery: () => '{}', transport: () => 'usb', version: () => 'test', haptic() {}, buzz() {}, setBrightness() {}, setVolumeKeys() {},
      phoneMedia: (key) => (mine(key) ? JSON.stringify(window.__pm.state) : '{}'),
      phoneMediaArt: (key) => (mine(key) ? 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="#e11d48"/></svg>') : ''),
      phoneMediaAction: (key, op, value) => { if (mine(key)) window.__pm.calls.push([op, value]); return true; },
      phoneVolume: (key, value) => { if (mine(key)) window.__pm.calls.push(['volume', value]); },
      openPhoneMediaAccess: (key) => { if (mine(key)) window.__pm.calls.push(['access']); },
    };
    const config = JSON.stringify({
      night: { enabled: false }, rules: [], shortcuts: [], display: { idleReturnSec: 0 },
      pages: [{ id: 'p', title: 'Phone', widgets: [{ id: 'pm', type: 'phone-media', x: 0, y: 0, w: 8, h: 6, options: {} }] }],
    });
    localStorage.setItem('cfg', config);
    localStorage.setItem('pb-demo-config', config);
  }, state);
  await page.goto('/panel/');
  return page.locator('.w-phone-media');
}

const calls = (page) => page.evaluate(() => window.__pm.calls);

test('asks for notification access and opens the setting on tap', async ({ page }) => {
  const w = await open(page, { access: false, media: null, audio: { volume: 40, muted: false, canControl: true } });
  await expect(w.locator('.m-title')).toHaveText('Allow notification access');
  await expect(w).toHaveClass(/idle/);
  await w.locator('[data-media="playpause"]').click();
  expect(await calls(page)).toEqual([['access']]);
});

test('shows the phone player and drives it', async ({ page }) => {
  const w = await open(page, {
    access: true,
    audio: { volume: 60, muted: false, canControl: true },
    media: { app: 'Spotify', title: 'Phone Song', artist: 'Someone', album: 'An Album', playing: true, posMs: 30_000, durMs: 120_000, canSeek: true, artId: 7 },
  });
  await expect(w.locator('.m-title')).toHaveText('Phone Song');
  await expect(w.locator('.m-artist')).toHaveText('Someone · An Album');
  await expect(w.locator('.m-app-name')).toHaveText('Spotify');
  await expect(w.locator('.m-dur')).toHaveText('2:00');
  await expect(w).toHaveClass(/playing/);
  await expect(w.locator('.art')).toHaveClass(/on/);
  await expect(w.locator('.vol-val')).toHaveText('60');
  await expect(w.locator('.offline-msg')).toHaveCount(0); // works without a computer

  await w.locator('[data-media="next"]').click();
  await w.locator('[data-media="playpause"]').click();
  await expect(w).not.toHaveClass(/playing/); // optimistic
  const bar = await w.locator('.bar').boundingBox();
  await page.mouse.click(bar.x + bar.width / 2, bar.y + bar.height / 2);
  await w.locator('.mute').click();
  const got = await calls(page);
  expect(got.slice(0, 2)).toEqual([['next', ''], ['playpause', '']]);
  const seek = got.find((c) => c[0] === 'seek');
  expect(Math.abs(+seek[1] - 60_000)).toBeLessThan(3_000);
  expect(got).toContainEqual(['volume', 'mute']);

  await page.evaluate(() => { window.__pm.state.media = null; });
  await expect(w.locator('.m-title')).toHaveText('Nothing playing');
  await expect(w.locator('.art')).not.toHaveClass(/on/);
});

test('in a browser without the phone bridge it says so', async ({ page }) => {
  await page.addInitScript(() => {
    const config = JSON.stringify({ night: { enabled: false }, rules: [], shortcuts: [],
      pages: [{ id: 'p', title: 'Phone', widgets: [{ id: 'pm', type: 'phone-media', x: 0, y: 0, w: 6, h: 4, options: {} }] }] });
    localStorage.setItem('cfg', config);
    localStorage.setItem('pb-demo-config', config);
  });
  await page.goto('/panel/');
  await expect(page.locator('.w-phone-media .m-artist')).toHaveText('Only on the phone');
});
