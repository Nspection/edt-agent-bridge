"""Build the standalone EDT Agent Bridge from explicit sources against a local EDT.
No original CodePilot bundle or embedded LLM libraries are used as compile dependencies.
"""
from pathlib import Path
import argparse,subprocess,re,json,zipfile,os,urllib.parse,urllib.request,sys,uuid
parser=argparse.ArgumentParser()
parser.add_argument('--edt-home',default=os.environ.get('CODEPILOT_EDT_HOME'))
parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
parser.add_argument('--qualifier',default='development')
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
if not args.edt_home or not args.java_home:parser.error('Set CODEPILOT_EDT_HOME and JAVA_HOME (JDK 17+)')
edt=Path(args.edt_home).resolve();java=Path(args.java_home).resolve()
if not re.fullmatch(r'[A-Za-z0-9_-]+',args.qualifier):parser.error('Invalid OSGi qualifier')
version='1.4.0.'+args.qualifier
out=root/'.runs/agent-bridge'/args.qualifier;out.mkdir(parents=True,exist_ok=True)
classes=out/('classes-'+uuid.uuid4().hex);classes.mkdir(exist_ok=False)
classpath=[]
for line in (edt/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info').read_text(encoding='utf-8-sig').splitlines():
 if line.startswith('#'):continue
 parts=line.split(',')
 if len(parts)<3 or parts[0].startswith('com.codepilot1c.'):continue
 uri=parts[2]
 if uri.startswith('file:'):p=Path(urllib.request.url2pathname(urllib.parse.urlsplit(uri).path))
 else:p=edt/uri
 if p.exists():classpath.append(str(p.resolve()))
if not classpath:raise RuntimeError('No EDT target bundles found')
source_list=(root/'agent-bridge/source-includes.txt').read_text(encoding='utf-8').splitlines()
sources=[]
for name in source_list:
 if not name or name.startswith('#'):continue
 p=(root/name).resolve()
 if not p.is_relative_to(root/'bundles') or p.suffix!='.java' or not p.is_file():raise RuntimeError('Invalid source entry: '+name)
 sources.append(p)
actual={p.resolve() for short in ['core','ui'] for p in (root/'bundles'/('com.codepilot1c.'+short)/'src').rglob('*.java')}
if actual!=set(sources):raise RuntimeError('Update agent-bridge/source-includes.txt to match all product Java sources')
quote=lambda s:'"'+str(s).replace('\\','/')+'"'
argfile=out/'javac.args'
argfile.write_text('\n'.join(['--release','17','-encoding','UTF-8','-Xmaxerrs','100','-classpath',quote(';'.join(classpath)),'-d',quote(classes)]+[quote(p) for p in sources]),encoding='utf-8')
with (out/'compile.log').open('w',encoding='utf-8') as log:
 result=subprocess.run([str(java/'bin/javac.exe'),'@'+str(argfile)],stdout=log,stderr=subprocess.STDOUT)
if result.returncode:
 print((out/'compile.log').read_text(encoding='utf-8')[-24000:]);raise SystemExit(result.returncode)
artifacts=[]
for short in ['core','ui']:
 bundle='com.codepilot1c.'+short;base=root/'bundles'/bundle
 manifest=(base/'META-INF/MANIFEST.MF').read_text(encoding='utf-8-sig')
 manifest=re.sub(r'Bundle-ClassPath:[\s\S]*?(?=\n\S|\Z)','Bundle-ClassPath: .',manifest)
 manifest=re.sub(r'Bundle-Version:.*','Bundle-Version: '+version,manifest)
 manifest=re.sub(r'Bundle-Name:.*','Bundle-Name: EDT Agent Bridge '+short.upper(),manifest)
 manifest=re.sub(r'Bundle-Vendor:.*','Bundle-Vendor: EDT Agent Bridge contributors; original CodePilot authors',manifest)
 pkgs=sorted({re.search(r'^package\s+([\w.]+);',p.read_text(encoding='utf-8-sig'),re.M)[1] for p in sources if p.is_relative_to(base)})
 manifest=re.sub(r'Export-Package:[\s\S]*?(?=\n\S|\Z)','Export-Package: '+',\n '.join(pkgs),manifest)
 if short=='ui':
  manifest=re.sub(r'^ org\.eclipse\.tm\.terminal[^\n]+\n','',manifest,flags=re.M)
 # JAR manifest physical lines must be at most 72 bytes (all header names above are ASCII).
 lines=[]
 for line in manifest.strip().splitlines():
  while len(line.encode('utf-8'))>70:lines.append(line[:70]);line=' '+line[70:]
  lines.append(line)
 payload=('\r\n'.join(lines)+'\r\n\r\n').encode('utf-8')
 jar=out/(bundle+'_'+version+'.jar')
 with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('META-INF/MANIFEST.MF',payload)
  for p in (classes/'com/codepilot1c'/short).rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
  for folder in ['OSGI-INF','schema','resources']:
   if (base/folder).exists():
    for p in (base/folder).rglob('*'):
     if p.is_file() and "__pycache__" not in p.parts and p.suffix.lower() not in {".pyc", ".pyo"}:z.write(p,p.relative_to(base).as_posix())
  z.write(base/'plugin.xml','plugin.xml')
  if short=='core':z.write(root/'tools/run-vanessa-debug.ps1','scripts/run-vanessa-debug.ps1')
  for p in (base/'src').rglob('*.properties'):z.write(p,p.relative_to(base/'src').as_posix())
  z.write(root/'LICENSE','LICENSE');z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
 artifacts.append(str(jar))
report={'version':version,'sources':len(sources),'classpath_entries':len(classpath),'artifacts':artifacts,'original_codepilot_on_classpath':False,'classpath':classpath}
(out/'build.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!="classpath"}))
