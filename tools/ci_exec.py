#!/usr/bin/env python3
"""Stream a build/test command and expose failures through API-visible annotations.

Only use for commands that do not print credentials. Complete logs are retained
as artifacts; annotations stay below GitHub's 4096-character message limit.
"""
from collections import deque
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def annotate(title, message):
    text = message[:3500].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::error title={title}::{text}', flush=True)


def main():
    if len(sys.argv) < 3:
        raise SystemExit('usage: ci_exec.py LOG_FILE COMMAND [ARGS...]')
    log = Path(sys.argv[1])
    log.parent.mkdir(parents=True, exist_ok=True)
    tail = deque(maxlen=100)
    diagnostics = deque(maxlen=100)
    per_file = {}
    with log.open('w') as output:
        process = subprocess.Popen(sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors='replace', bufsize=1)
        for line in process.stdout:
            print(line, end='', flush=True)
            output.write(line)
            tail.append(line)
            if any(marker in line.lower() for marker in (
                    'e: ', 'error:', 'what went wrong', 'execution failed', 'failed', 'incompatible', 'was compiled')):
                key = line.split(':', 2)[1] if line.startswith('e: file://') else line[:32]
                per_file[key] = per_file.get(key, 0) + 1
                if per_file[key] <= 8:
                    diagnostics.append(line)
        code = process.wait()
    if code and os.getenv('GITHUB_ACTIONS') == 'true':
        annotate('Build diagnostics', ''.join(diagnostics))
        count = 0
        patterns = ('**/build/test-results/**/TEST-*.xml', '**/build/outputs/androidTest-results/**/*.xml')
        for pattern in patterns:
            for report in Path('.').glob(pattern):
                try:
                    root = ET.parse(report).getroot()
                    for case in root.iter('testcase'):
                        for failure in list(case.findall('failure')) + list(case.findall('error')):
                            if count < 5:
                                annotate('JUnit failure', f"{case.get('classname')}.{case.get('name')}:\n{failure.text or failure.get('message', '')}")
                                count += 1
                except (ET.ParseError, OSError):
                    continue
        for report in Path('.').glob('**/build/reports/lint-results-*.xml'):
            try:
                for issue in ET.parse(report).getroot().findall('issue'):
                    if issue.get('severity') in ('Error', 'Fatal', 'Warning') and count < 7:
                        location = issue.find('location')
                        annotate('Lint issue', f"{issue.get('id')}: {issue.get('message')}\n{location.attrib if location is not None else ''}")
                        count += 1
            except (ET.ParseError, OSError):
                continue
        annotate('Build log tail', ''.join(tail)[-3500:])
    return code


if __name__ == '__main__':
    sys.exit(main())
