#!/usr/bin/env python3
"""Measure actual APK ZIP sizes and guard the production native-library layout."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import zipfile


def inspect(path):
    groups = collections.Counter()
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        abis = sorted({name.split('/')[1] for name in names if name.startswith('lib/')})
        if len(abis) != 1:
            raise ValueError(f'{path}: expected one ABI, found {abis}')
        abi = abis[0]
        required = {'libsherpa-onnx-jni.so', 'libonnxruntime.so'}
        if abi in ('arm64-v8a', 'armeabi-v7a'):
            required |= {'libspeechengine.so', 'libaudioeffect.so'}
        for name in required:
            if f'lib/{abi}/{name}' not in names:
                raise ValueError(f'{path}: missing {name}')
        for name in names:
            if name.endswith(('/libsherpa-onnx-c-api.so', '/libsherpa-onnx-cxx-api.so')):
                raise ValueError(f'{path}: unused Sherpa wrapper packaged: {name}')
        for name in ('encoder.int8.onnx', 'decoder.onnx', 'joiner.int8.onnx', 'tokens.txt', 'zh.phone', 'en.phone'):
            if f'assets/sherpa-kws/{name}' not in names:
                raise ValueError(f'{path}: missing offline wake resource {name}')
        for item in apk.infolist():
            name = item.filename
            group = ('native' if name.startswith('lib/') else
                     'dex' if name.endswith('.dex') else
                     'wake' if name.startswith('assets/sherpa-kws/') else 'other')
            groups[group] += item.compress_size
    size = path.stat().st_size
    groups['other'] += size - sum(groups.values())
    return {'file': path.name, 'abi': abi, 'bytes': size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'compressed_bytes': dict(groups)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--max-arm64-mb', type=float)
    args = parser.parse_args()
    results = [inspect(path) for path in args.apks]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2) + '\n')
    print('| APK | ABI | MB | Native MB | DEX MB | Wake MB |')
    print('|---|---|---:|---:|---:|---:|')
    for result in results:
        groups = result['compressed_bytes']
        print(f"| {result['file']} | {result['abi']} | {result['bytes']/1e6:.2f} | "
              f"{groups['native']/1e6:.2f} | {groups['dex']/1e6:.2f} | {groups['wake']/1e6:.2f} |")
        if (args.max_arm64_mb is not None and result['abi'] == 'arm64-v8a'
                and result['bytes'] > args.max_arm64_mb * 1e6):
            raise SystemExit(f"ARM64 APK exceeds {args.max_arm64_mb} MB budget")


if __name__ == '__main__':
    main()
