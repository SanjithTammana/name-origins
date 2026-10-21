import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { recordForIssue } from './index-feedback.mjs';

const repository = 'SanjithTammana/name-origins';
const issue = {
  number: 42,
  created_at: '2026-10-20T00:00:00Z',
  body: '### Name checked\n\nSeoah\n\n### Result category\n\nLetter-pattern guess\n\n'
    + '### What seems wrong?\n\nThe site guessed Hebrew.\n\n'
    + '### What should it say, if you know?\n\nKorean usage may fit.\n',
};

test('indexes only audit metadata and keeps the public issue as the full report', () => {
  const record = recordForIssue({ issue }, repository);
  assert.deepEqual(record, {
    issue: 42,
    name: 'Seoah',
    category: 'Letter-pattern guess',
    created_at: '2026-10-20T00:00:00Z',
    issue_url: 'https://github.com/SanjithTammana/name-origins/issues/42',
  });
  assert.equal(JSON.stringify(record).includes('The site guessed'), false);
});

test('ignores unrelated or incomplete issues', () => {
  assert.equal(recordForIssue({ issue: { ...issue, body: 'A normal issue' } }, repository), null);
  assert.equal(recordForIssue({ issue: { ...issue, number: '../42' } }, repository), null);
  assert.equal(recordForIssue({ issue: { ...issue, pull_request: {} } }, repository), null);
});

test('limits a hostile name to one short JSON field and a numeric filename', () => {
  const body = issue.body.replace('Seoah', '<img src=x onerror=alert(1)>\nextra');
  const record = recordForIssue({ issue: { ...issue, body } }, repository);
  assert.equal(record.name, '<img src=x onerror=alert(1)>');
  assert.equal(record.issue, 42);
});

test('the workflow command writes the expected issue-number file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'name-feedback-'));
  try {
    const eventPath = join(dir, 'event.json');
    const outputDir = join(dir, 'reports');
    writeFileSync(eventPath, JSON.stringify({ issue }));
    const run = spawnSync(process.execPath, ['scripts/index-feedback.mjs', eventPath, outputDir], {
      cwd: new URL('..', import.meta.url),
      env: { ...process.env, GITHUB_REPOSITORY: repository },
      encoding: 'utf8',
    });
    assert.equal(run.status, 0, run.stderr);
    const saved = JSON.parse(readFileSync(join(outputDir, '000042.json'), 'utf8'));
    assert.deepEqual(saved, recordForIssue({ issue }, repository));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
