"""Synthetic local checks; these never contact a device or model provider."""
import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import checkpoint as cp
import validate_evidence as ev


class Helpers(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.path = self.root/'checkpoint.json'
        self.locks = self.root/'locks'

    def do(self, command, **kw):
        args = dict(command=command, path=self.path, lock_root=self.locks,
                    run_id='task', serial='synthetic-device', owner='synthetic-agent')
        args.update(kw)
        return cp.execute(argparse.Namespace(**args))

    def acquired(self):
        self.do('init'); self.do('acquire')

    def test_other_task_cannot_acquire_same_device(self):
        self.acquired()
        other = self.root/'other.json'; self.do('init', path=other, run_id='other', owner='other')
        with self.assertRaisesRegex(ValueError, '另一任务'):
            self.do('acquire', path=other)
        self.do('release'); self.do('acquire', path=other)

    def test_reacquire_does_not_lose_progress(self):
        self.acquired(); self.do('begin', case='TC-1', step='S1', action='synthetic send')
        result=self.do('acquire')
        self.assertEqual(result['pending']['step_id'], 'S1')

    def test_pending_prevents_replay_and_release(self):
        self.acquired(); self.do('begin', case='TC-1', step='S1', action='send')
        for command, kw in [('begin', dict(case='TC-1', step='S1', action='send again')), ('release', {})]:
            with self.assertRaises(ValueError): self.do(command, **kw)
        self.do('resolve', note='Readback: no submitted request; abort attempt.')
        self.do('release')
        self.assertEqual(len(cp.read(self.path)['resolutions']), 1)

    def test_init_no_overwrite(self):
        self.do('init')
        with self.assertRaises(ValueError): self.do('init')

    def test_update_cannot_change_identity(self):
        self.acquired(); patch=self.root/'patch.json'
        cp.atomic(patch, {'serial':'different'})
        with self.assertRaises(ValueError): self.do('update', patch=patch)
        self.assertEqual(cp.read(self.path)['serial'], 'synthetic-device')

    def fact(self, time=None):
        self.acquired(); self.do('begin', case='TC-1', step='S1', action='send')
        run=self.root/'r1'; run.mkdir()
        cp.atomic(run/'meta.json', {'id':'r1', 'case':{'id':'TC-1'}})
        fact={'step_id':'S1', 'started_at':time or cp.now(), 'ended_at':time or cp.now(), 'action':'send',
              'assertions':[], 'evidence':[]}
        cp.atomic(run/'S1-fact.json', fact)
        (run/'facts.jsonl').write_text(json.dumps(fact)+'\n')
        return run/'S1-fact.json'

    def test_complete_requires_successful_workflow_append(self):
        fact=self.fact(); (fact.parent/'facts.jsonl').write_text('')
        with self.assertRaisesRegex(ValueError, '尚未成功追加'):
            self.do('complete', fact=fact, status='INCONCLUSIVE')
        self.assertIsNotNone(cp.read(self.path)['pending'])

    def test_complete_rejects_previous_run_fact(self):
        fact=self.fact('2020-01-01T00:00:00Z')
        with self.assertRaisesRegex(ValueError, '早于本次'):
            self.do('complete', fact=fact, status='PASS')

    def test_complete_records_run_and_preserves_resource_state(self):
        fact=self.fact(); patch=self.root/'patch.json'
        cp.atomic(patch, {'resources':[{'kind':'device', 'keep':True}]})
        self.do('update', patch=patch)
        self.do('complete', fact=fact, status='INCONCLUSIVE'); self.do('release')
        state=cp.read(self.path)
        self.assertIsNone(state['pending']); self.assertEqual(state['completed'][0]['run_id'], 'r1')
        self.assertEqual(state['resources'], [{'kind':'device', 'keep':True}])

    def test_concurrent_acquire_has_one_winner(self):
        paths=[self.root/'a.json', self.root/'b.json']
        for i,path in enumerate(paths): self.do('init', path=path, owner=str(i), run_id=str(i))
        processes=[subprocess.Popen([sys.executable,str(SCRIPTS/'checkpoint.py'),'acquire','--path',str(p),
                                     '--lock-root',str(self.locks)],stdout=subprocess.PIPE,stderr=subprocess.PIPE) for p in paths]
        for p in processes: p.communicate(timeout=10)
        self.assertEqual(sorted(p.returncode for p in processes), [0,1])

    def fake_adb(self, missing=False):
        script=self.root/'fake-adb'
        script.write_text('''#!/usr/bin/env python3
import sys
a=sys.argv[3:]
values={
 ('get-state',):'device',
 ('shell','getprop','ro.serialno'):'synthetic-serial',
 ('shell','getprop','ro.build.version.release'):'16',
 ('shell','getprop','ro.build.version.sdk'):'36',
 ('shell','wm','size'):'Physical size: 1200x2608\\nOverride size: 1080x2340',
 ('version',):'Android Debug Bridge synthetic',
}
if a[:3]==['shell','dumpsys','package']:
 print('No package' if MISSING else 'Package [example.app] (synthetic):\\n versionCode=7 minSdk=28\\n versionName=1.2')
else: print(values[tuple(a)])
'''.replace('MISSING', repr(missing)))
        script.chmod(0o700)
        return script

    def capture(self, missing=False):
        return ev.capture(argparse.Namespace(out=self.root/'task', adb=str(self.fake_adb(missing)),
                                            adb_target='synthetic-target', package='example.app'))

    def test_capture_uses_actual_output_and_workflow_accepts(self):
        result=self.capture(); self.assertEqual(result['version'],'1.2 (7)')
        self.assertEqual(result['screen'],'1080x2340')
        w=ev.workflow(None); env=w.executor_environment(self.root/'task'/'r1')
        self.assertEqual(env['device_serial'], 'synthetic-serial')
        self.assertEqual(len(env['capture_evidence']), 7)
        with self.assertRaises(ValueError): self.capture()

    def test_capture_rejects_missing_package(self):
        with self.assertRaisesRegex(ValueError, '未找到目标已安装包'): self.capture(missing=True)
        self.assertFalse((self.root/'task'/'environment.json').exists())
        self.assertTrue((self.root/'task'/'environment-capture'/'package.json').exists())


if __name__ == '__main__':
    unittest.main()
