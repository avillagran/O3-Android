#!/usr/bin/env python3
"""Opt-in real T3/QUIC/JNI lab. Requires an already opened Android emulator.
No productive base directory, endpoint or credential is accepted as an argument.
T3 logs are private and removed with the fresh lab directories on exit.
"""
import argparse
import json
import os
import re
from pathlib import Path
import selectors
import shutil
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--t3-binary', required=True, type=Path)
parser.add_argument('--bridge-source', type=Path, default=Path.home() / 'Work/omabridge')
parser.add_argument('--serial', default='emulator-5580', help='Explicit emulator serial; physical devices are rejected')
parser.add_argument('--skip-build', action='store_true', help='Reuse exact-source lab-tls artifacts; Gradle still verifies packaged bytes')
parser.add_argument('--full-ui', action='store_true', help='Also run the complete app instrumentation suite')
args = parser.parse_args()
if not re.fullmatch(r'emulator-[0-9]+', args.serial):
    parser.error('--serial must identify an Android emulator')
assert args.t3_binary.is_absolute() and args.t3_binary.is_file(), 'Absolute T3 executable required'
assert args.bridge_source.is_absolute(), 'Absolute bridge source required'
pin = dict(line.split('=', 1) for line in (ROOT/'libs/omabridge-native.lock').read_text().splitlines())['source']
head = subprocess.check_output(['git', '-C', str(args.bridge_source), 'rev-parse', 'HEAD'], text=True).strip()
assert pin == head, 'Bridge source does not match lock'
assert not subprocess.check_output(['git', '-C', str(args.bridge_source), 'status', '--porcelain']), 'Commit bridge source before verification'
env = os.environ.copy()
env.update(ANDROID_SERIAL=args.serial, OMABRIDGE_DIR=str(args.bridge_source), OMABRIDGE_LAB_TLS='1')
env.setdefault('JAVA_HOME', str(Path.home()/'.local/jdk'))
env.setdefault('ANDROID_HOME', str(Path.home()/'.android-sdk'))
# Build outside the readiness window: compiling examples is not network startup.
subprocess.run(['cargo', 'build', '--release', '--locked', '--example', 'catalog_mobile_lab',
                '--example', 'catalog_t3_lab'], cwd=args.bridge_source, env=env, check=True)
metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--locked', '--no-deps',
    '--format-version', '1'], cwd=args.bridge_source, env=env, text=True))
example_directory = Path(metadata['target_directory']) / 'release' / 'examples'
if not args.skip_build:
    subprocess.run([str(ROOT/'scripts/build-bridge-sdk.sh')], cwd=ROOT, env=env, check=True)
work = Path(tempfile.mkdtemp(prefix='obv-'))
os.chmod(work, 0o700)
# Linux sockaddr_un has108bytes including its terminating NUL.
if len(os.fsencode(work/'catalog_t3_lab'/'owner'/'control.sock')) >= 108:
    shutil.rmtree(work)
    raise RuntimeError('Choose a shorter TMPDIR for the isolated control socket')
processes = []
ports = []
logs = []
try:
    for example in ('catalog_mobile_lab', 'catalog_t3_lab'):
        log = open(work/(example+'.private.log'), 'wb')
        os.chmod(log.name, 0o600)
        logs.append(log)
        command = [str(example_directory/example), str(work/example)]
        if example == 'catalog_t3_lab': command.append(str(args.t3_binary))
        process = subprocess.Popen(command, cwd=args.bridge_source, stdout=subprocess.PIPE, stderr=log, text=True)
        processes.append(process)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + 120
        port = None
        while time.monotonic() < deadline:
            if process.poll() is not None: raise RuntimeError('Lab exited before readiness; private logs removed on cleanup')
            if selector.select(timeout=1):
                line = process.stdout.readline()
                if line:
                    port = json.loads(line)['port']
                    break
        selector.close()
        if port is None: raise RuntimeError('Lab readiness deadline exceeded')
        if example == 'catalog_t3_lab' and not (work/example/'owner'/'control.sock').is_socket():
            raise RuntimeError('Isolated delegated bridge has no control socket')
        ports.append(port)
    command = [str(ROOT/'gradlew'), '--no-daemon', ':core:testDebugUnitTest', ':core:lintDebug', ':core:connectedDebugAndroidTest',
               '-Pot3a.requireBridgeSdk=true', '-Pot3a.requireHostJni=true', '-Pot3a.bridgeLab=true',
               f'-Pandroid.injected.device.serial={args.serial}',
               f'-Pandroid.testInstrumentationRunnerArguments.catalogLabPort={ports[0]}',
               f'-Pandroid.testInstrumentationRunnerArguments.realT3LabPort={ports[1]}', '--console=plain']
    subprocess.run(command, cwd=ROOT, env=env, check=True, timeout=600)
    if args.full_ui:
        subprocess.run([str(ROOT/'gradlew'), '--no-daemon', ':app:connectedDebugAndroidTest', '-Pot3a.requireBridgeSdk=true', '-Pot3a.requireHostJni=true', '-Pot3a.bridgeLab=true', f'-Pandroid.injected.device.serial={args.serial}', '-Pandroid.testInstrumentationRunnerArguments.listener=dev.avillagran.ot3android.testing.FailureDiagnosticsListener', '-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/media/dev.avillagran.ot3android/bridge-output', '--console=plain'], cwd=ROOT, env=env, check=True)
    suites = [ET.parse(file).getroot() for file in (ROOT/'core/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml')]
    real = [case for suite in suites for case in suite.findall('testcase') if case.attrib.get('classname', '').endswith('BridgeT3EndToEndTest')]
    assert len(real) == 1 and not any(real[0].find(key) is not None for key in ('skipped','failure','error')), 'Unified real T3 test must run and pass, never skip'
    print('Unified Android/JNI/two-bridge QUIC/real T3 verification passed; no production or physical-device claim.')
finally:
    for port in ports:
        try: urllib.request.urlopen(f'http://127.0.0.1:{port}/stop', timeout=15).read()
        except Exception: pass
    for process in processes:
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
    for log in logs: log.close()
    shutil.rmtree(work)
