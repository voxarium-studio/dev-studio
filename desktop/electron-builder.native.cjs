const common = require('./electron-builder.cjs');

// Dev spec §2.7.1: only the payload changes. Keep the original install and
// uninstall identity, scope, shortcuts and replacement script.
module.exports = {
  ...common,
  directories: { ...common.directories, output: 'dist/native' },
  nsis: { ...common.nsis, packElevateHelper: false },
};
