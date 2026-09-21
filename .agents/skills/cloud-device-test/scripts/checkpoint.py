#!/usr/bin/env python3
"""Local device ownership and resumable progress. Never sends device actions."""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import uuid


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='milliseconds')


def instant(value):
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    need(parsed.tzinfo is not None, '事实时间必须带时区')
    return parsed


def read(path):
    return json.loads(Path(path).read_text())


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write('\n')
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


@contextlib.contextmanager
def guard(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        yield


def need(value, message):
    if not value:
        raise ValueError(message)


def subset(a, b):
    if isinstance(a, dict):
        return isinstance(b, dict) and all(k in b and subset(v, b[k]) for k, v in a.items())
    if isinstance(a, list):
        return isinstance(b, list) and len(a) == len(b) and all(subset(x, y) for x, y in zip(a, b))
    return type(a) is type(b) and a == b


def execute(args):
    path = args.path.resolve()
    with guard(path.with_name('.' + path.name + '.guard')):
        if args.command == 'init':
            need(not path.exists(), '检查点已存在；先读取，不覆盖')
            state = {'schema': 'cloud-device-checkpoint/v1', 'run_id': args.run_id,
                     'serial': args.serial, 'owner': args.owner, 'token': uuid.uuid4().hex,
                     'created_at': now(), 'updated_at': now(), 'pending': None,
                     'completed': [], 'resolutions': [], 'remaining': [], 'resources': []}
            atomic(path, state)
            return state
        state = read(path)
        need(state.get('schema') == 'cloud-device-checkpoint/v1', '未知检查点格式')
        if args.command == 'show':
            return state
        key = hashlib.sha256(state['serial'].encode()).hexdigest()
        lock = args.lock_root.expanduser() / (key + '.json')
        with guard(lock.with_suffix('.guard')):
            current = read(lock) if lock.exists() else None
            own = current and current.get('token') == state['token'] and current.get('checkpoint') == str(path)
            if args.command == 'acquire':
                need(current is None or own, '设备已由另一任务持有: ' + json.dumps(current, ensure_ascii=False))
                atomic(lock, {'serial': state['serial'], 'owner': state['owner'],
                              'run_id': state['run_id'], 'token': state['token'],
                              'checkpoint': str(path), 'updated_at': now()})
            else:
                need(own, '当前检查点没有设备动作锁，先 acquire；不能抢占其他任务')
                if args.command == 'begin':
                    need(state['pending'] is None, '存在未决动作；先读回设备结果，再 complete 或 resolve')
                    state['pending'] = {'case_id': args.case, 'step_id': args.step,
                                        'action': args.action, 'started_at': now()}
                elif args.command == 'complete':
                    pending = state['pending']
                    need(pending is not None, '没有未决动作')
                    fact = read(args.fact)
                    run_dir = args.fact.resolve().parent
                    meta = read(run_dir / 'meta.json')
                    need(meta['case']['id'] == pending['case_id'], '事实的 case 与未决动作不符')
                    need(fact.get('step_id') == pending['step_id'], '事实 step 与未决动作不符')
                    need(instant(fact['started_at']) >= instant(pending['started_at']),
                         '事实早于本次未决动作，不能用旧轮次事实完成当前动作')
                    need(instant(fact['ended_at']) >= instant(fact['started_at']), '事实时间倒序')
                    records = [json.loads(line) for line in (run_dir / 'facts.jsonl').read_text().splitlines() if line.strip()]
                    need(any(subset(fact, row) for row in records), '事实尚未成功追加到 workflow，或与已追加事实不符')
                    state['completed'].append({**pending, 'run_id': meta['id'], 'status': args.status,
                                               'fact': str(args.fact.resolve()), 'finished_at': now()})
                    state['pending'] = None
                elif args.command == 'resolve':
                    need(state['pending'] is not None, '没有未决动作')
                    need(bool(args.note.strip()), '必须说明设备读回证据与下一步')
                    state['resolutions'].append({**state['pending'], 'note': args.note, 'at': now()})
                    state['pending'] = None
                elif args.command == 'update':
                    patch = read(args.patch)
                    allowed = {'foreground', 'lease', 'artifact', 'config', 'remaining', 'resources', 'notes'}
                    need(isinstance(patch, dict) and set(patch) <= allowed, 'patch 包含不可修改字段')
                    state.update(patch)
                elif args.command == 'release':
                    need(state['pending'] is None, '未决动作尚未解决，不能释放动作锁')
                    lock.unlink()
            state['updated_at'] = now()
            atomic(path, state)
            return {'command': args.command, 'checkpoint': str(path), 'pending': state['pending'],
                    'completed_steps': len(state['completed']), 'lock': str(lock)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('init', 'show', 'acquire', 'begin', 'complete', 'resolve', 'update', 'release'):
        p = sub.add_parser(name)
        p.add_argument('--path', type=Path, required=True)
        p.add_argument('--lock-root', type=Path, default=Path.home() / '.cache/cloud-device-test/locks')
        if name == 'init':
            for key in ('run-id', 'serial', 'owner'):
                p.add_argument('--' + key, required=True)
        if name == 'begin':
            for key in ('case', 'step', 'action'):
                p.add_argument('--' + key, required=True)
        if name == 'complete':
            p.add_argument('--fact', type=Path, required=True)
            p.add_argument('--status', required=True, choices=['PASS', 'FAIL', 'ERROR', 'BLOCKED', 'INCONCLUSIVE'])
        if name == 'resolve':
            p.add_argument('--note', required=True)
        if name == 'update':
            p.add_argument('--patch', type=Path, required=True)
    try:
        print(json.dumps(execute(parser.parse_args()), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError) as exc:
        print(json.dumps({'error': str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
