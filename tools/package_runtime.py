"""Package a built server with externally supplied matching WZ assets; never deploy."""
from pathlib import Path
import argparse,hashlib,json,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--wz-archive',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1]
assert a.wz_archive.is_file() and (r/'target/Cosmic.jar').is_file()
baseline=json.loads((r/'release/baseline.json').read_text())
assert hashlib.sha256(a.wz_archive.read_bytes()).hexdigest()==baseline['assets']['server_wz_tar_sha256'],'Wrong server WZ archive'
assert hashlib.sha256((r/'target/Cosmic.jar').read_bytes()).hexdigest()==baseline['source_baseline_server_jar_sha256'],'Server JAR does not match baseline verification'
assert not a.output.exists(),'Output already exists'
files={'target/Cosmic.jar':r/'target/Cosmic.jar','config.example.yaml':r/'config.example.yaml','wz.tar.gz':a.wz_archive,'unpack_assets.py':r/'tools/unpack_assets.py','BASELINE.json':r/'release/baseline.json'}
for name in ['scripts','cashshop','deploy','database/migrations']:
 for f in (r/name).rglob('*'):
  if f.is_file():files[f.relative_to(r).as_posix()]=f
manifest={'repository':'https://github.com/joshhmann/SlumsMS','commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip(),'files':{n:hashlib.sha256(f.read_bytes()).hexdigest() for n,f in files.items()},'private_config_included':False,'database_dump_included':False}
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',allowZip64=True) as z:
 for n,f in files.items():z.write(f,n,compress_type=zipfile.ZIP_STORED if n.endswith('.gz') else zipfile.ZIP_DEFLATED)
 z.writestr('manifest.json',json.dumps(manifest,indent=2))
with zipfile.ZipFile(a.output) as z:assert z.testzip() is None
print(json.dumps({'archive':str(a.output),'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'files':len(files)}))
