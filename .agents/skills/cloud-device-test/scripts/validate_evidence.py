#!/usr/bin/env python3
"""Capture read-only Android environment or verify files. Does not judge product behavior."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

from checkpoint import atomic


FIELDS = ('ui_platform', 'target', 'version', 'os', 'runtime', 'browser', 'viewport',
          'device_serial', 'adb_target', 'package', 'screen', 'captured_by', 'captured_at', 'capture_evidence')


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def need(value, message):
    if not value:
        raise ValueError(message)


def capture(args):
    need(bool(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', args.package)), '无效 Android 包名')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    need(not (out / 'environment.json').exists(), '已有环境记录；创建新环境目录，不覆盖')
    raw = out / 'environment-capture'
    raw.mkdir()  # Exclusive capture directory; failed captures remain inspectable.
    evidence = []

    def adb(name, *command):
        argv = [args.adb, '-s', args.adb_target, *command]
        started = now()
        result = subprocess.run(argv, capture_output=True, text=True, timeout=45)
        record = {'argv': argv, 'started_at': started, 'ended_at': now(),
                  'exit_code': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
        path = raw / (name + '.json')
        atomic(path, record)
        evidence.append({'path': str(path.relative_to(out)), 'sha256': sha(path.read_bytes())})
        need(result.returncode == 0, '环境采集命令失败，见 ' + str(path))
        return result.stdout.strip()

    need(adb('state', 'get-state') == 'device', 'ADB 设备未在线')
    serial = adb('serial', 'shell', 'getprop', 'ro.serialno')
    if not serial:
        serial = adb('serial-fallback', 'get-serialno')
    release = adb('android', 'shell', 'getprop', 'ro.build.version.release')
    sdk = adb('sdk', 'shell', 'getprop', 'ro.build.version.sdk')
    screen_raw = adb('screen', 'shell', 'wm', 'size')
    sizes = re.findall(r'(?:Physical|Override) size:\s*(\d+x\d+)', screen_raw)
    need(bool(sizes), '无法解析设备屏幕尺寸')
    package_raw = adb('package', 'shell', 'dumpsys', 'package', args.package)
    need(bool(re.search(r'Package \[' + re.escape(args.package) + r'\]', package_raw)), '未找到目标已安装包')
    name = re.search(r'\bversionName=([^\r\n]+)', package_raw)
    code = re.search(r'\bversionCode=(\d+)', package_raw)
    need(name is not None and code is not None, '无法读取已安装包版本')
    runtime = adb('adb-version', 'version')
    need(all((serial, release, sdk, runtime)), '环境读回字段为空')
    env = {'ui_platform': 'android-native', 'target': 'android://' + args.package,
           'version': name.group(1).strip() + ' (' + code.group(1) + ')',
           'os': 'Android ' + release + ' SDK ' + sdk, 'runtime': runtime,
           'browser': '不适用', 'viewport': '不适用', 'device_serial': serial,
           'adb_target': args.adb_target, 'package': args.package, 'screen': sizes[-1],
           'captured_by': 'cloud-device-test/android-adb-v1', 'captured_at': now(),
           'capture_evidence': evidence, 'executor_fields': list(FIELDS)}
    env['executor_sha256'] = sha(json.dumps({k: env[k] for k in FIELDS}, ensure_ascii=False, sort_keys=True).encode())
    atomic(out / 'environment.json', env)
    return {'environment': str(out / 'environment.json'), 'package': args.package,
            'version': env['version'], 'screen': env['screen'], 'raw_records': len(evidence)}


def workflow(path):
    candidates = [path] if path else [Path.home() / '.agents/skills/test-workflow/scripts/workflow.py',
                                    Path.home() / '.codex/skills/test-workflow/scripts/workflow.py']
    target = next((p for p in candidates if p.is_file()), None)
    need(target is not None, '未找到 test-workflow；传 --workflow <workflow.py>')
    spec = importlib.util.spec_from_file_location('cloud_test_workflow', target)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_run(args):
    root = args.run_dir.resolve()
    w = workflow(args.workflow)
    meta = w.read(root / 'meta.json')
    w.validate_case(meta['case'])
    need(meta['case_sha256'] == w.digest(meta['case']), '用例被改动')
    facts = w.load_facts(root)
    checked = []
    media = []
    for fact in facts:
        w.validate_fact(meta, fact, checked, root)
        checked.append(fact)
        media.extend(fact['evidence'])
    if (root / 'run.json').exists():
        run = w.checked_run(root / 'run.json')
        video = run['completion'].get('recording')
        if video:
            media.append(video['evidence'])
    observed = {f['step_id'] for f in facts}
    missing = [s['id'] for s in meta['case']['steps'] if s['core'] and s['id'] not in observed]
    ffmpeg = shutil.which('ffmpeg')
    results = []
    for item in media:
        path = (root / item['path']).resolve()
        need(path.is_relative_to(root), '证据路径越界')
        row = {'path': item['path'], 'sha256': sha(path.read_bytes()), 'file_check': 'ok'}
        if item['type'] in ('image', 'video'):
            if not ffmpeg:
                row.update(file_check='error', error='缺少 ffmpeg，未解码')
            else:
                result = subprocess.run([ffmpeg, '-v', 'error', '-xerror', '-i', str(path),
                                         '-map', '0:v:0', '-f', 'null', '-'], capture_output=True, text=True, timeout=180)
                if result.returncode:
                    row.update(file_check='error', error=result.stderr[-1200:])
        results.append(row)
    return {'purpose': '仅校验文件与记录；业务结论由 test-workflow 和实际预期判断',
            'file_checks': results, 'missing_steps': missing,
            'complete': not missing and all(x['file_check'] == 'ok' for x in results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('capture-env')
    p.add_argument('--adb-target', required=True)
    p.add_argument('--package', required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--adb', default='adb', help='ADB 可执行文件；离线自测可注入测试程序')
    p = sub.add_parser('check-run')
    p.add_argument('--run-dir', type=Path, required=True)
    p.add_argument('--workflow', type=Path)
    args = parser.parse_args()
    try:
        result = capture(args) if args.command == 'capture-env' else check_run(args)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result.get('complete', True) else 1
    except (ValueError, OSError, KeyError, subprocess.TimeoutExpired) as exc:
        print(json.dumps({'error': str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
