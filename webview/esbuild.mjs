import { mkdir, readFile, writeFile } from 'node:fs/promises';
import * as esbuild from 'esbuild';

const production = process.argv.includes('--production');
const watch = process.argv.includes('--watch');

/**
 * Two scripts and one stylesheet, all for the browser. The plugin writes them
 * into the panel's document as inline elements, so nothing here may be loaded
 * by URL.
 *
 * @type {import('esbuild').BuildOptions}
 */
const options = {
  bundle: true,
  minify: production,
  // The scripts are inlined, so a source map has to travel inside them.
  sourcemap: production ? false : 'inline',
  target: 'es2022',
  logLevel: 'info',
  entryPoints: { panel: 'src/webview/main.ts', shim: 'src/bridge/main.ts', host: 'src/bridge/host.css' },
  outdir: 'dist',
  platform: 'browser',
  format: 'iife',
  loader: { '.css': 'css' },
};

/** The theme variables the stylesheet reads. The plugin has to supply each one. */
async function writeThemeVariables() {
  const css = (await readFile('src/webview/styles.css', 'utf8')) + (await readFile('src/bridge/host.css', 'utf8'));
  const names = [...new Set(css.match(/--(?:vscode|entire)-[\w-]+/g) ?? [])].sort();
  await mkdir('dist', { recursive: true });
  await writeFile('dist/theme-variables.json', `${JSON.stringify(names, null, 2)}\n`);
}

/**
 * An inline script ends at the first closing script tag, and an inline
 * stylesheet at the first closing style tag, wherever it appears.
 */
async function assertInlineSafe() {
  const checks = [
    ['dist/panel.js', /<\/script/i],
    ['dist/shim.js', /<\/script/i],
    ['dist/panel.css', /<\/style/i],
    ['dist/host.css', /<\/style/i],
  ];
  for (const [file, closing] of checks) {
    if (closing.test(await readFile(file, 'utf8'))) {
      throw new Error(`${file} contains ${closing.source}, so it cannot be inlined into the panel's document.`);
    }
  }
}

if (watch) {
  await writeThemeVariables();
  const context = await esbuild.context(options);
  await context.watch();
} else {
  await esbuild.build(options);
  await writeThemeVariables();
  await assertInlineSafe();
}
