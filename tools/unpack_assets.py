"""Extract only a supplied WZ tree, refusing links and out-of-tree paths."""
from pathlib import Path
import argparse,tarfile
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,default=Path('wz.tar.gz'));p.add_argument('--destination',type=Path,default=Path('.'));a=p.parse_args();root=a.destination.resolve();root.mkdir(parents=True,exist_ok=True)
with tarfile.open(a.archive) as t:
 for entry in t.getmembers():
  target=(root/entry.name).resolve();target.relative_to(root/'wz')
  assert entry.isfile() or entry.isdir(),'Links and special files are unsupported'
 t.extractall(root)
print('WZ assets extracted; configure the private database/settings before starting the server')
