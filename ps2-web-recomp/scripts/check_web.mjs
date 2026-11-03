import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';

if (process.argv.length < 3) {
  console.error('usage: node scripts/check_web.mjs <page.html> [...]');
  process.exit(1);
}
for (const file of process.argv.slice(2)) {
  const html = readFileSync(file, 'utf8');
  let count = 0;
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    if (!match[1].trim()) continue;
    new Script(match[1], { filename: `${file}:inline-${++count}` });
  }
  if (!count) throw new Error(`${file}: no inline scripts found`);
  console.log(`syntax OK: ${file} (${count} inline scripts)`);
}
