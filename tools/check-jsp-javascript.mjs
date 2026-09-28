import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

// Parse inline JavaScript with representative JSP expression values.
// Runtime-rendered pages are checked separately by the application smoke test.
for (const name of ['dashboard', 'inspection']) {
  const file = `src/main/resources/webapp/WEB-INF/views/${name}.jsp`;
  const html = readFileSync(file, 'utf8');
  let count = 0;
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) {
    const script = match[1].replace(/<%=[\s\S]*?%>/g, 'JSP_VALUE');
    const checked = spawnSync(process.execPath, ['--check'], { input: script, encoding: 'utf8' });
    if (checked.status !== 0) {
      process.stderr.write(`${file}: ${checked.stderr}`);
      process.exit(1);
    }
    count++;
  }
  if (!count) throw new Error(`No inline scripts checked: ${file}`);
  console.log(`${file}: ${count} script(s) passed node --check`);
}
