"""Check direct product bytecode references against its actual OSGi visibility.

Uses only the selected EDT target and JDK. Reflection and application behavior
still require live tests; a flat javac classpath cannot verify bundle visibility.
"""
from pathlib import Path
import json,zipfile,re,struct,collections

def audit(build,java_home):
    M=json.loads((Path(build)/'build.json').read_text(encoding='utf-8'))
    def manifest(data):
     t=data.decode('utf8').replace('\r\n','\n').replace('\n ','');return dict(line.split(': ',1) for line in t.splitlines() if ': ' in line)
    def clauses(value):return [part.strip() for part in re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)',value) if part.strip()]
    def name(clause):return clause.split(';')[0].strip()
    bundles={};providers=collections.defaultdict(list)
    for f in M['classpath']+M['artifacts']:
     p=Path(f)
     try:
      if p.is_dir():mf=manifest((p/'META-INF/MANIFEST.MF').read_bytes())
      else:
       with zipfile.ZipFile(p) as z:mf=manifest(z.read('META-INF/MANIFEST.MF'))
     except (OSError,KeyError,zipfile.BadZipFile):continue
     symbol=name(mf.get('Bundle-SymbolicName',''));exports={name(c) for c in clauses(mf.get('Export-Package',''))};bundles[symbol]=(mf,exports)
     for pkg in exports:providers[pkg].append(symbol)
    def required_exports(symbol,seen=None):
     seen=set() if seen is None else seen
     if symbol in seen or symbol not in bundles:return set()
     seen.add(symbol);mf,exports=bundles[symbol];out=set(exports)
     for clause in clauses(mf.get('Require-Bundle','')):
      if 'visibility:=reexport' in clause:out|=required_exports(name(clause),seen)
     return out
    boot=set()
    for f in (Path(java_home)/'jmods').glob('*.jmod'):
     with zipfile.ZipFile(f) as z:
      boot|={n[8:].rsplit('/',1)[0].replace('/','.') for n in z.namelist() if n.startswith('classes/') and n.endswith('.class') and '/' in n[8:]}
    def references(data):
     count=struct.unpack_from('>H',data,8)[0];i=1;pos=10;utf={};classes=[]
     while i<count:
      tag=data[pos];pos+=1
      if tag==1:
       size=struct.unpack_from('>H',data,pos)[0];pos+=2;utf[i]=data[pos:pos+size].decode('utf8',errors='replace');pos+=size
      elif tag==7:classes.append(struct.unpack_from('>H',data,pos)[0]);pos+=2
      elif tag in (3,4,9,10,11,12,17,18):pos+=4
      elif tag in (5,6):pos+=8;i+=1
      elif tag in (8,16,19,20):pos+=2
      elif tag==15:pos+=3
      else:raise ValueError(tag)
      i+=1
     refs=set()
     for index in classes:
      value=utf[index]
      if value.startswith('['):refs.update(re.findall(r'L([A-Za-z0-9_$/]+);',value))
      else:refs.add(value)
     return {v.rsplit('/',1)[0].replace('/','.') for v in refs if '/' in v}
    report={}
    for f in M['artifacts']:
     with zipfile.ZipFile(f) as z:
      mf=manifest(z.read('META-INF/MANIFEST.MF'));symbol=name(mf['Bundle-SymbolicName']);own={n.rsplit('/',1)[0].replace('/','.') for n in z.namelist() if n.endswith('.class')};allowed=own|boot|{name(c) for c in clauses(mf.get('Import-Package',''))}
      for c in clauses(mf.get('Require-Bundle','')):allowed|=required_exports(name(c))
      missing=collections.defaultdict(list)
      for n in z.namelist():
       if n.endswith('.class'):
        for pkg in references(z.read(n))-allowed:
         if len(missing[pkg])<4:missing[pkg].append(n)
      report[symbol]={pkg:dict(references=refs,providers=providers[pkg]) for pkg,refs in sorted(missing.items())}
    return report

if __name__ == '__main__':
    import argparse,os
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build',required=True)
    parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
    args=parser.parse_args()
    if not args.java_home or not (Path(args.java_home)/'jmods').is_dir():
        parser.error('JDK17+ with jmods is required')
    report=audit(args.build,args.java_home)
    (Path(args.build)/'linkage-audit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    if any(report.values()):
        print(json.dumps(report,indent=2))
        raise SystemExit(1)
    print('PASS: direct bytecode OSGi visibility for all product bundles')
