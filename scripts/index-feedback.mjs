import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

function formField(body, label) {
  const text = body.replace(/\r\n/g, '\n');
  const marker = `### ${label}\n`;
  const start = text.indexOf(marker);
  if (start < 0) return '';
  const rest = text.slice(start + marker.length);
  const next = rest.search(/\n### /);
  return (next < 0 ? rest : rest.slice(0, next)).trim();
}

/** A public issue is the report. The repository file is only a small audit pointer. */
export function recordForIssue(event, repository) {
  const issue = event?.issue;
  if (!issue || issue.pull_request || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository ?? '')) {
    return null;
  }
  const number = issue.number;
  if (!Number.isSafeInteger(number) || number <= 0) return null;
  const body = typeof issue.body === 'string' ? issue.body : '';
  const rawName = formField(body, 'Name checked');
  const problem = formField(body, 'What seems wrong?');
  if (!rawName || !problem || problem === 'No response') return null;

  // Store no free-text allegation, source URL, or other personal details in the repository index.
  const name = rawName.split('\n', 1)[0].replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 100);
  if (!name || name === 'No response') return null;
  const type = formField(body, 'Result category');
  const categories = new Set(['Recorded entry', 'Spelling variant', 'Letter-pattern guess', 'No result']);
  return {
    issue: number,
    name,
    category: categories.has(type) ? type : 'Unspecified',
    created_at: issue.created_at,
    issue_url: `https://github.com/${repository}/issues/${number}`,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const eventPath = process.argv[2] || process.env.GITHUB_EVENT_PATH;
  const outputDir = process.argv[3] || 'feedback/reports';
  if (!eventPath) throw new Error('Expected a GitHub issue event JSON path');
  const event = JSON.parse(readFileSync(eventPath, 'utf8'));
  const record = recordForIssue(event, process.env.GITHUB_REPOSITORY);
  if (record) {
    mkdirSync(outputDir, { recursive: true });
    const file = join(outputDir, `${String(record.issue).padStart(6, '0')}.json`);
    writeFileSync(file, JSON.stringify(record, null, 2) + '\n', 'utf8');
    console.log(`Indexed issue #${record.issue} in ${file}`);
  } else {
    console.log('Issue is not a complete name feedback form; nothing indexed.');
  }
}
