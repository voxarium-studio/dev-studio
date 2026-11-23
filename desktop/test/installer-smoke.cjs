// Opt-in Windows integration test: isolated app ID/registry, tiny payload, no Studio server.
// Run with `node test/installer-smoke.cjs` from desktop. Artifacts stay in .scratch/.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const { once } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');
const desktop = path.resolve(__dirname, '..');
const root = path.resolve(desktop, '../.scratch/installer-smoke');
const installation = path.join(root, "Existing folder's name");
const guid = '8c0bb992-702a-4a21-a599-d12e0898ac53';
const productName = 'Voxarium Installer Smoke 01a18d0b';
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
function install(version, args = []) {
  return run(path.join(root, 'dist', `setup-${version}.exe`), ['/S', '/currentuser', ...args], { windowsVerbatimArguments: true });
}
async function main() {
  assert.equal(process.platform, 'win32');
  assert.notEqual(run('reg.exe', ['query', registry, '/reg:64']).status, 0, 'A previous smoke installation remains; uninstall it first.');
  assert.equal(fs.existsSync(profile), false, 'Refusing to reuse an existing profile.');
  fs.mkdirSync(path.join(payload, 'resources'), { recursive: true });
  fs.mkdirSync(profile);
  fs.writeFileSync(marker, 'settings stay');
  fs.writeFileSync(path.join(root, 'project-data.txt'), 'project stays');
  let worker;
  let workerExit;
  try {
    for (const version of ['1.0.0', '2.0.0']) {
      fs.writeFileSync(path.join(payload, `${productName}.exe`), `test payload ${version}`);
      const old = path.join(payload, 'obsolete.txt');
      if (version === '1.0.0') fs.writeFileSync(old, 'removed by upgrade');
      else fs.unlinkSync(old);
      const config = path.join(root, `config-${version}.cjs`);
      fs.writeFileSync(config, `const base = require(${JSON.stringify(path.join(desktop, 'electron-builder.cjs'))});
require(${JSON.stringify(path.join(desktop, 'scripts/installer-messages.cjs'))}).writeInstallerMessages('abcdef0');
module.exports = { ...base, appId: 'studio.voxarium.installer-smoke', productName: ${JSON.stringify(productName)},
  artifactName: 'setup-${version}.exe', extraMetadata: { version: '${version}', studioVersion: 'abcdef0' }, publish: null,
  directories: { ...base.directories, output: ${JSON.stringify(path.join(root, 'dist'))} },
  nsis: { ...base.nsis, guid: '${guid}', include: ${version === '1.0.0' ? 'null' : JSON.stringify(path.join(desktop, 'installer/update.nsh'))},
    createDesktopShortcut: false, createStartMenuShortcut: false, runAfterFinish: false } };
`);
      const output = successful(process.execPath, [path.join(desktop, 'node_modules/electron-builder/cli.js'),
        '--config', config, '--prepackaged', payload, '--win', 'nsis', '--x64', '--publish', 'never']);
      fs.writeFileSync(path.join(root, `build-${version}.log`), output);
    }
    assert.equal(install('1.0.0', [`/D=${installation}`]).status, 0);
    assert.equal(fs.readFileSync(path.join(installation, `${productName}.exe`), 'utf8'), 'test payload 1.0.0');
    assert.ok(successful('reg.exe', ['query', registry, '/v', 'InstallLocation', '/reg:64']).includes(installation));

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
          { stdio: 'ignore', windowsHide: false });
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
    assert.equal(fs.existsSync(path.join(installation, 'obsolete.txt')), false);
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayVersion', '/reg:64']).includes('abcdef0'));
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayName', '/reg:64']).includes(`${productName} abcdef0`));
    assert.equal(fs.readFileSync(marker, 'utf8'), 'settings stay');
    assert.equal(fs.readFileSync(path.join(root, 'project-data.txt'), 'utf8'), 'project stays');
    assert.equal(install('2.0.0', ['--updated']).status, 0, 'Updater mode must support reinstall');
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayVersion', '/reg:64']).includes('abcdef0'));
    assert.ok(successful('reg.exe', ['query', uninstallRegistry, '/v', 'DisplayName', '/reg:64']).includes(`${productName} abcdef0`));
    assert.equal(fs.readFileSync(marker, 'utf8'), 'settings stay');
    console.log('PASS: legacy install, busy-process guard, termination, in-place commit upgrade, displayed commit version/name, obsolete-file cleanup, profile/project preservation, updater reinstall');
  } finally {
    if (worker) { worker.kill(); await workerExit; }
    const uninstaller = path.join(installation, `Uninstall ${productName}.exe`);
    if (fs.existsSync(uninstaller)) {
      successful(uninstaller, ['/S', '/currentuser'], { windowsVerbatimArguments: true });
      for (let attempt = 0; attempt < 60 && run('reg.exe', ['query', registry, '/reg:64']).status === 0; attempt++) await delay(500);
      assert.notEqual(run('reg.exe', ['query', registry, '/reg:64']).status, 0, 'Test uninstallation did not finish');
    }
    fs.unlinkSync(marker);
    fs.rmdirSync(profile);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
