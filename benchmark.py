"""Compare retained builds on identical inputs. Desktop timings are not camera timings.

python benchmark.py --input photo.jpg --baseline out/baseline/grain-process.exe
Results and processed copies are written only under out/benchmark.
"""
from argparse import ArgumentParser
from pathlib import Path
import hashlib
import json
import statistics
import subprocess
import time

root = Path(__file__).resolve().parent
p = ArgumentParser()
p.add_argument('--input', type=Path, required=True)
p.add_argument('--baseline', type=Path)
p.add_argument('--runs', type=int, default=3)
args = p.parse_args()
if args.runs < 1:
    p.error('--runs must be positive')
source = args.input.resolve()
output = root / 'out/benchmark'
output.mkdir(parents=True, exist_ok=True)
current = root / 'out/host/grain-process.exe'
cases = {
    'Off': [0,2,1,0,30,75,0,40,0,0,0,0,0,0,0,0],
    'Grain': [35,2,1,0,30,75,0,40,0,0,0,0,0,0,0,0],
    'Bloom': [0,2,1,0,30,75,30,40,0,0,0,0,0,0,0,0],
    'Combined': [35,2,1,40,30,75,30,40,20,20,20,20,15,50,30,20],
}
results = {'input': str(source), 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'runs': args.runs, 'cases': {}}
for name, values in cases.items():
    row = {}
    executables = [('current', current)]
    if args.baseline:
        executables.insert(0, ('baseline', args.baseline.resolve()))
    for label, exe in executables:
        elapsed = []
        target = output / (source.stem + '-' + name.lower() + '-' + label + '.jpg')
        for _ in range(args.runs):
            start = time.perf_counter()
            run = subprocess.run([str(exe), str(source), str(target), *map(str, values)], capture_output=True, text=True, check=True)
            elapsed.append(time.perf_counter() - start)
        row[label + '_seconds'] = statistics.median(elapsed)
        if label == 'current':
            row['native_report'] = run.stdout.strip()
    if args.baseline:
        row['speedup'] = row['baseline_seconds'] / row['current_seconds']
    results['cases'][name] = row
    print(name, json.dumps(row), flush=True)
half = output / (source.stem + '-combined-half.jpg')
elapsed = []
for _ in range(args.runs):
    start = time.perf_counter()
    run = subprocess.run([str(current), str(source), str(half), *map(str, cases['Combined']), '1'], capture_output=True, text=True, check=True)
    elapsed.append(time.perf_counter() - start)
results['half_combined_seconds'] = statistics.median(elapsed)
(output / (source.stem + '-results.json')).write_text(json.dumps(results, indent=2))
print('Half combined:', results['half_combined_seconds'], 'seconds', flush=True)
