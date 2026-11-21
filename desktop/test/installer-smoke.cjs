// Opt-in Windows integration test: Electron -> Kotlin via an isolated NSIS identity.
// Both packages use tiny fixture payloads and run no Studio server.
// Run with `node test/installer-smoke.cjs` from desktop. Build artifacts stay in .scratch/.
const fs = require('node:fs');
const { homedir } = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');
const { once } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const { installerMessages } = require('../scripts/installer-messages.cjs');
const desktop = path.resolve(__dirname, '..');
const scratch = path.resolve(desktop, '../.scratch');
fs.mkdirSync(scratch, { recursive: true });
const root = fs.mkdtempSync(path.join(scratch, 'kotlin-installer-smoke-'));
const guid = randomUUID();
const productName = `Voxarium Installer Smoke ${guid.slice(0, 8)}`;
const registry = `HKCU\\Software\\${guid}`;
const uninstallRegistry = `HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\${guid}`;
const payload = path.join(root, 'payload');
const profile = path.join(process.env.APPDATA, productName);
const marker = path.join(profile, 'preserve-test.txt');
function run(exe, args, options = {}) {
  const result = spawnSync(exe, args, { cwd: desktop, windowsHide: true, encoding: 'utf8', timeout: 120_000, ...options });
  if (result.error) throw result.error;
  return result;
}
function successful(exe, args, options) {
  const result = run(exe, args, options);
  assert.equal(result.status, 0, `${exe}: ${result.stdout}\n${result.stderr}`);
  return result.stdout;
}
async function main() {
  assert.equal(process.platform, 'win32');
  const electronConfig = require('../electron-builder.cjs');
  const kotlinConfig = require('../electron-builder.native.cjs');
  assert.equal(kotlinConfig.appId, electronConfig.appId);
  for (const key of ['guid', 'perMachine', 'oneClick', 'include', 'deleteAppDataOnUninstall']) {
    assert.equal(kotlinConfig.nsis[key], electronConfig.nsis[key], `Both packages must share NSIS ${key}`);
  }
  assert.notEqual(run('reg.exe', ['query', registry, '/reg:64']).status, 0, 'A previous smoke installation remains; uninstall it first.');
  assert.equal(fs.existsSync(profile), false, 'Refusing to reuse an existing profile.');
  const profileHome = fs.realpathSync(homedir());
  const fixturePrefix = 'vx-smoke-';
  const fixtureHome = fs.mkdtempSync(path.join(profileHome, fixturePrefix));
  const installation = path.join(fixtureHome, "Existing folder's name");
  const longTemp = path.join(fixtureHome, `temp-${'x'.repeat(56)}`);
  const setupEnv = { ...process.env, TEMP: longTemp, TMP: longTemp };
  function install(version, args = []) {
    return run(path.join(root, 'dist', `setup-${version}.exe`), ['/S', '/currentuser', ...args], {
      windowsVerbatimArguments: true, env: setupEnv,
    });
  }
  let worker;
  let workerExit;
  try {
    fs.mkdirSync(longTemp);
    fs.mkdirSync(path.join(payload, 'resources'), { recursive: true });
    const buildResources = path.join(root, 'build-resources');
    fs.mkdirSync(buildResources);
    fs.writeFileSync(path.join(buildResources, 'installer-messages.nsh'), installerMessages({
      ENGLISH: require('../../frontend/src/locales/en.json').installer,
      RUSSIAN: require('../../frontend/src/locales/ru.json').installer,
    }, 'abcdef0'));
    fs.mkdirSync(profile);
    fs.writeFileSync(marker, 'settings stay');
    fs.writeFileSync(path.join(root, 'project-data.txt'), 'project stays');
    for (const version of ['1.0.0', '2.0.0']) {
      const kotlin = version === '2.0.0';
      fs.writeFileSync(path.join(payload, `${productName}.exe`), `test payload ${version}`);
      const asar = path.join(payload, 'resources', 'app.asar');
      if (kotlin) {
        fs.unlinkSync(asar);
        fs.mkdirSync(path.join(payload, 'runtime', 'bin'), { recursive: true });
        fs.writeFileSync(path.join(payload, 'runtime', 'bin', 'java.dll'), 'Kotlin runtime marker');
      } else fs.writeFileSync(asar, 'Electron package marker');
      const config = path.join(root, `config-${version}.cjs`);
      const sourceConfig = kotlin ? 'electron-builder.native.cjs' : 'electron-builder.cjs';
      fs.writeFileSync(config, `const base = require(${JSON.stringify(path.join(desktop, sourceConfig))});
module.exports = { ...base, appId: 'studio.voxarium.installer-smoke.${guid}', productName: ${JSON.stringify(productName)},
  artifactName: 'setup-${version}.exe', extraMetadata: { version: '${version}', studioVersion: 'abcdef0' }, publish: null,
  directories: { ...base.directories, output: ${JSON.stringify(path.join(root, 'dist'))}, buildResources: ${JSON.stringify(buildResources)} },
  nsis: { ...base.nsis, guid: '${guid}', include: ${version === '1.0.0' ? 'null' : JSON.stringify(path.join(desktop, 'installer/update.nsh'))},
    createDesktopShortcut: false, createStartMenuShortcut: false, runAfterFinish: false } };
`);
      const output = successful(process.execPath, [path.join(desktop, 'node_modules/electron-builder/cli.js'),
        '--config', config, '--prepackaged', payload, '--win', 'nsis', '--x64', '--publish', 'never']);
      fs.writeFileSync(path.join(root, `build-${version}.log`), output);
    }
    assert.equal(install('1.0.0', [`/D=${installation}`]).status, 0);
    assert.equal(fs.readFileSync(path.join(installation, `${productName}.exe`), 'utf8'), 'test payload 1.0.0');
    assert.equal(fs.readFileSync(path.join(installation, 'resources', 'app.asar'), 'utf8'), 'Electron package marker');
    assert.ok(successful('reg.exe', ['query', registry, '/v', 'InstallLocation', '/reg:64']).includes(installation));

    // A nested Python plugin file fits in the installed tree, but the old NSIS
    // uninstaller's backup under the padded TEMP exceeds the Win32 directory limit.
    const pluginPath = path.join('app', 'resources', 'studio-backend', 'python', 'Lib', 'site-packages',
      'voxarium_studio', 'plugins_builtin', 'hunyuan3d', '.venv', 'Lib', 'site-packages', 'licenses');
    const lengthBudget = 245 - path.join(installation, pluginPath).length - 1;
    assert.ok(lengthBudget > 0, 'Profile path is too long for the test fixture');
    const nestedPath = path.join(pluginPath, 'x'.repeat(lengthBudget));
    assert.ok(path.join(longTemp, 'nsx1.tmp', 'old-install', nestedPath).length > 248);
    assert.ok(path.join(profileHome, 'nsxFFFF.tmp', 'old-install', nestedPath).length <= 248);
    const legacyFile = path.join(installation, nestedPath, 'LICENSE');
    fs.mkdirSync(path.dirname(legacyFile), { recursive: true });
    fs.writeFileSync(legacyFile, 'Legacy plugin marker');

    // A bundled worker must block replacement even if no Electron window exists.
    const workerExe = path.join(installation, 'worker.exe');
    fs.copyFileSync(process.execPath, workerExe);
    worker = spawn(workerExe, ['-e', 'setInterval(() => {}, 1000)'], { windowsHide: true, stdio: 'ignore' });
    workerExit = once(worker, 'exit');
    await once(worker, 'spawn');
    assert.equal(install('2.0.0').status, 2, 'Busy installation must fail before replacing files');
    assert.equal(fs.readFileSync(path.join(installation, `${productName}.exe`), 'utf8'), 'test payload 1.0.0');
    if (process.argv.includes('--interactive')) {
      // Drive the real native dialog manually or with desktop automation: first
      // Retry/Cancel, then Yes/Finish. The fixture never starts the Studio backend.
      for (const expected of [2, 0]) {
        console.log(expected === 2 ? 'UI: retry the busy check, then cancel' : 'UI: confirm process termination, then finish');
        const setup = spawn(path.join(root, 'dist', 'setup-2.0.0.exe'), ['/currentuser'],
          { stdio: 'ignore', windowsHide: false, env: setupEnv });
        assert.equal((await once(setup, 'exit'))[0], expected);
        if (expected === 2) {
          assert.doesNotThrow(() => process.kill(worker.pid, 0), 'Cancel must leave the worker running');
          assert.equal(fs.readFileSync(path.join(installation, `${productName}.exe`), 'utf8'), 'test payload 1.0.0');
        }
      }
    } else {
      successful('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', path.join(desktop, 'scripts/check-installation.ps1'), '-Directory', installation,
        '-Terminate', '-WaitSeconds', '10']);
    }
    await workerExit;
    worker = null;

    // No /D: the update must retain even a directory without the product name.
    assert.equal(install('2.0.0').status, 0);
    assert.equal(fs.readFileSync(path.join(installation, `${productName}.exe`), 'utf8'), 'test payload 2.0.0');
    assert.equal(fs.existsSync(path.join(installation, 'resources', 'app.asar')), false);
    assert.equal(fs.existsSync(legacyFile), false, 'The upgrade should remove the long legacy path');
    assert.equal(fs.readFileSync(path.join(installation, 'runtime', 'bin', 'java.dll'), 'utf8'), 'Kotlin runtime marker');
    assert.ok(successful('reg.exe', ['query', registry, '/v', 'InstallLocation', '/reg:64']).includes(installation));
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayVersion', '/reg:64']).includes('abcdef0'));
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayName', '/reg:64']).includes(`${productName} abcdef0`));
    assert.equal(fs.readFileSync(marker, 'utf8'), 'settings stay');
    assert.equal(fs.readFileSync(path.join(root, 'project-data.txt'), 'utf8'), 'project stays');
    assert.equal(install('2.0.0', ['--updated']).status, 0, 'Updater mode must support reinstall');
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayVersion', '/reg:64']).includes('abcdef0'));
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayName', '/reg:64']).includes(`${productName} abcdef0`));
    assert.equal(fs.readFileSync(marker, 'utf8'), 'settings stay');
    console.log('PASS: Electron -> Kotlin, registered custom directory, busy guard, long legacy path removed, Kotlin files installed, profile/project preserved, updater reinstall');
  } finally {
    if (worker) { worker.kill(); await workerExit; }
    const uninstaller = path.join(installation, `Uninstall ${productName}.exe`);
    if (fs.existsSync(uninstaller)) {
      successful(uninstaller, ['/S', '/currentuser'], { windowsVerbatimArguments: true });
      for (let attempt = 0; attempt < 60 && run('reg.exe', ['query', registry, '/reg:64']).status === 0; attempt++) await delay(500);
      assert.notEqual(run('reg.exe', ['query', registry, '/reg:64']).status, 0, 'Test uninstallation did not finish');
    }
    if (fs.existsSync(marker)) fs.unlinkSync(marker);
    if (fs.existsSync(profile)) fs.rmdirSync(profile);
    const cleanup = fs.realpathSync(fixtureHome);
    assert.equal(path.dirname(cleanup), profileHome);
    assert.ok(path.basename(cleanup).startsWith(fixturePrefix));
    fs.rmSync(cleanup, { recursive: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
