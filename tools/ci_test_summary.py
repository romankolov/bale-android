#!/usr/bin/env python3
"""Summarize the actual JUnit reports produced by this runner, without guessing counts."""
from collections import defaultdict
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

counts = defaultdict(lambda: {'tests': 0, 'failures': 0, 'skipped': 0})
reports = sorted(set(Path('.').glob('**/build/test-results/**/TEST-*.xml')) |
                 set(Path('.').glob('**/build/outputs/androidTest-results/**/*.xml')))
for report in reports:
    root = ET.parse(report).getroot()
    project = str(report).split('/build/')[0]
    for case in root.iter('testcase'):
        counts[project]['tests'] += 1
        counts[project]['failures'] += int(case.find('failure') is not None or case.find('error') is not None)
        counts[project]['skipped'] += int(case.find('skipped') is not None)

summary = {'report_files': len(reports), 'projects': dict(counts)}
Path('ci-logs').mkdir(exist_ok=True)
Path('ci-logs/test-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
text = json.dumps(summary)
print(text)
if os.getenv('GITHUB_ACTIONS') == 'true':
    print('::notice title=JUnit report summary::' + text.replace('%', '%25'))
if os.getenv('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
        output.write('## Executed JUnit tests\n\n| Project | Tests | Failures | Skipped |\n|---|---:|---:|---:|\n')
        for name, row in sorted(counts.items()):
            output.write(f"| {name} | {row['tests']} | {row['failures']} | {row['skipped']} |\n")
        if not reports:
            output.write('\nNo JUnit reports were produced; this does not mean tests passed.\n')
