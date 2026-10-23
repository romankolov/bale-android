#!/usr/bin/env python3
"""Stream a CI command, retain its log, and expose failures as check annotations.

Annotations make build failures inspectable through the GitHub API even when
Actions log-download hosts are inaccessible from the development workspace.
Only use for build/test commands, never commands that print credentials.
"""
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque


def main():
    if len(sys.argv) < 3:
        raise SystemExit('usage: ci_exec.py LOG_FILE COMMAND [ARGS...]')
    log = Path(sys.argv[1])
    log.parent.mkdir(parents=True, exist_ok=True)
    tail = deque(maxlen=180)
    diagnostics = deque(maxlen=120)
    with log.open('w') as output:
        process = subprocess.Popen(sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors='replace', bufsize=1)
        for line in process.stdout:
            print(line, end='', flush=True)
            output.write(line)
            tail.append(line)
            if any(marker in line.lower() for marker in ('e: ', 'error:', 'what went wrong', 'execution failed', 'failed', 'incompatible', 'was compiled')):
                diagnostics.append(line)
        code = process.wait()
    if code and os.getenv('GITHUB_ACTIONS') == 'true':
        text = 'Compiler/failure diagnostics:\n' + ''.join(diagnostics)[-12000:] + '\nLog tail:\n' + ''.join(tail)[-12000:]
        for pattern in ('**/build/test-results/**/TEST-*.xml', '**/build/outputs/androidTest-results/**/*.xml'):
            for report in Path('.').glob(pattern):
                try:
                    root = ET.parse(report).getroot()
                    for case in root.iter('testcase'):
                        for failure in list(case.findall('failure')) + list(case.findall('error')):
                            text += f"\n{case.get('classname')}.{case.get('name')}:\n{failure.text or failure.get('message', '')}\n"
                except (ET.ParseError, OSError):
                    continue
        text = text[-48000:]
        text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::error title=Build or test command failed::{text}', flush=True)
    return code


if __name__ == '__main__':
    sys.exit(main())
