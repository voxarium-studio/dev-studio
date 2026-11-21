// Run with the checkout's Electron, not with Node and not through Studio's main process.
const assert = require('node:assert/strict');
const { copyFileSync, mkdirSync } = require('node:fs');
const path = require('node:path');
const { app, BrowserWindow, session } = require('electron');

const desktop = path.resolve(__dirname, '..');
const tempArg = process.argv[2];
assert.ok(tempArg, 'Supply a working directory for the probe profile and packaging fixture');
const scratch = path.resolve(tempArg, 'splash-probe');
const profile = path.join(scratch, 'profile');
mkdirSync(profile, { recursive: true });
app.setPath('userData', profile);
app.setPath('sessionData', profile);
app.commandLine.appendSwitch('disable-background-networking');
app.commandLine.appendSwitch('host-resolver-rules', 'MAP * ~NOTFOUND');
app.on('window-all-closed', () => {}); // The probe opens both layouts sequentially.

const mixed = 'Voxarium Студия Ёж';
const allo = 'Allo Round 100';
const inter = 'Inter Variable';

function stageSplash() {
  const root = path.join(scratch, 'packaged-layout');
  const copy = (from, to) => {
    mkdirSync(path.dirname(to), { recursive: true });
    copyFileSync(from, to);
  };
  for (const file of ['splash.html', 'splash.js', 'assets/logo.png']) {
    copy(path.join(desktop, file), path.join(root, file));
  }
  // Follow the actual electron-builder mappings, including both OFL notices.
  const { build: { files } } = require('../package.json');
  for (const dir of ['fonts/allo', 'fonts/inter']) {
    const mapping = files.find(item => item.to === dir);
    assert.ok(mapping, dir);
    for (const file of mapping.filter) {
      copy(path.resolve(desktop, mapping.from, file), path.join(root, dir, file));
    }
  }
  return path.join(root, 'splash.html');
}

async function check(label, file) {
  const window = new BrowserWindow({ show: false, width: 460, height: 300,
    webPreferences: { sandbox: true, backgroundThrottling: false } });
  try {
    const contents = window.webContents;
    await contents.loadFile(file, { query: { message: mixed } });
    const debuggerClient = contents.debugger;
    debuggerClient.attach('1.3');
    const send = (...args) => debuggerClient.sendCommand(...args);
    await send('DOM.enable');
    await send('CSS.enable');
    const sample = async (selector, families, weight) => {
      const { root } = await send('DOM.getDocument');
      const { nodeId } = await send('DOM.querySelector', { nodeId: root.nodeId, selector });
      const { fonts } = await send('CSS.getPlatformFontsForNode', { nodeId });
      assert.deepEqual(fonts.map(font => font.familyName).sort(), [...families].sort());
      assert.ok(fonts.every(font => font.isCustomFont && font.glyphCount > 0));
      const actual = await contents.executeJavaScript(
        'getComputedStyle(document.querySelector(' + JSON.stringify(selector) + ')).fontWeight');
      assert.equal(actual, weight);
      if (weight === '700') assert.ok(fonts.every(font => font.postScriptName.includes('Bold')));
      console.log(label, selector, weight, fonts.map(
        ({postScriptName, glyphCount}) => postScriptName + ':' + glyphCount).join(', '));
    };
    const waitForFonts = () => contents.executeJavaScript(
      'document.body.getBoundingClientRect(); document.fonts.ready.then(() => document.body.offsetWidth)');
    await waitForFonts();
    await sample('h1', [allo], '600');
    await sample('#status', [allo, inter], '400');
    await contents.executeJavaScript(
      'document.querySelector("h1").textContent = ' + JSON.stringify(mixed) + ';' +
      'document.querySelector("h1").style.fontWeight = "700";' +
      'document.querySelector("#status").textContent = "СтудияЁж";');
    await waitForFonts();
    await sample('h1', [allo, inter], '700');
    await sample('#status', [inter], '400');
  } finally {
    window.destroy();
  }
}

const timeout = setTimeout(() => {
  console.error('Timed out reading the splash renderer');
  app.exit(1);
}, 30_000);
app.whenReady().then(async () => {
  session.defaultSession.webRequest.onBeforeRequest(
    { urls: ['http://*/*', 'https://*/*'] }, (_details, respond) => respond({ cancel: true }));
  console.log('Electron', process.versions.electron, 'Chromium', process.versions.chrome);
  await check('checkout splash', path.join(desktop, 'splash.html'));
  await check('packaged-layout splash', stageSplash());
}).then(() => app.exit(0), error => {
  console.error(error);
  app.exit(1);
}).finally(() => clearTimeout(timeout));
