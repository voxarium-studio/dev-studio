const path = require('node:path');
const { releaseInfo } = require('./scripts/release.cjs');
const { updateFeedOptions } = require('./update-feed.cjs');
const metadata = require('./package.json');
const fs = require('node:fs');
const stampFile = path.join(__dirname, 'build/runtime/python/Lib/site-packages/voxarium_studio/_build_stamp.json');
const stamp = fs.existsSync(stampFile) ? JSON.parse(fs.readFileSync(stampFile, 'utf8')) : { version: metadata.version };
const release = releaseInfo(stamp, metadata.version);
require('./scripts/installer-messages.cjs').writeInstallerMessages(release.display);
const feed = updateFeedOptions(process.env);
// `compression: normal` means Deflate level 7 for the ZIP payload, and 7za spends 130 s of one
// thread on it, most of that on the two 300 MB executables. Level 5 packs the same payload in
// 50 s for 3 MB more (level 1: 14 s, 22 MB more). The level has no config key, only this
// variable, so a build can still choose its own.
process.env.ELECTRON_BUILDER_COMPRESSION_LEVEL ??= '5';
module.exports = {
  ...metadata.build,
  publish: feed ? [feed] : null,
  extraMetadata: { version: release.installerVersion, studioVersion: release.version },
  // Git tags can contain slashes; filenames and latest.yml must use a safe single component.
  artifactName: `Voxarium-Studio-Setup-${release.display.replace(/[^a-zA-Z0-9._+-]/g, '-')}.\${ext}`,
  // Resolve from this file so a different shell directory cannot select another build.
  directories: { ...metadata.build.directories, app: path.resolve(__dirname) },
};
