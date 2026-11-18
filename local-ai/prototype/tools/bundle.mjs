// Inlines CSS and JS into one self-contained HTML file (for sharing as a single page).
// Usage: node local-ai/prototype/tools/bundle.mjs <out.html> [--fragment]
//   --fragment  omit <!doctype>/<html>/<head>/<body> wrappers (for hosts that add their own skeleton)
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.argv[2];
if (!out) { console.error('usage: bundle.mjs <out.html> [--fragment]'); process.exit(2); }
const fragment = process.argv.includes('--fragment');
let html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
html = html.replace(/<link rel="stylesheet" href="(css\/[^"]+)">/g, (_, f) => `<style>\n${fs.readFileSync(path.join(root, f), 'utf8')}\n</style>`);
html = html.replace(/<script src="(js\/[^"]+)"><\/script>/g, (_, f) => `<script>\n${fs.readFileSync(path.join(root, f), 'utf8').replace(/<\/script/gi, '<\\/script')}\n</script>`);
if (fragment) {
  html = html.replace(/<!doctype html>\s*/i, '').replace(/<html[^>]*>\s*/i, '').replace(/<\/html>\s*/i, '')
    .replace(/<head>\s*/i, '').replace(/<\/head>\s*/i, '').replace(/<body>\s*/i, '').replace(/<\/body>\s*/i, '')
    .replace(/<meta charset="utf-8">\s*/i, '').replace(/<meta name="viewport"[^>]*>\s*/i, '')
    .replace('<title>', '<meta name="claude:viewport-fit-sides" content="cover">\n<title>');
}
fs.writeFileSync(out, html);
console.log('wrote', out, (html.length / 1024).toFixed(0) + ' KB');
