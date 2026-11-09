"""Build Windows x64 Native API transport; outputs stay outside Git."""
from pathlib import Path
import argparse,subprocess,os,json,hashlib,zipfile
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--diagnostics',action='store_true');p.add_argument('--compiler');a=p.parse_args()
r=Path(__file__).resolve().parent.parent;o=Path(a.output).resolve();o.mkdir(parents=True,exist_ok=True)
vs=Path(os.environ.get('ProgramFiles','C:/Program Files'))/'Microsoft Visual Studio'
candidates=sorted(vs.glob('*/*/VC/Tools/MSVC/*/bin/Hostx64/x64/cl.exe'))
if not a.compiler and not candidates:p.error('Install MSVC x64 build tools or pass --compiler')
compiler=Path(a.compiler).resolve() if a.compiler else candidates[-1]
msvc=compiler.parents[3];kits=Path(os.environ.get('ProgramFiles(x86)','C:/Program Files (x86)'))/'Windows Kits/10';version=sorted((kits/'Include').iterdir())[-1].name
cmd=[str(compiler),'/nologo','/LD','/MT','/std:c++17','/EHsc','/utf-8','/D_WINDOWS','/DNOMINMAX','/I'+str(r/'native/agent-host/include'),'/I'+str(msvc/'include')]
if a.diagnostics:cmd+=['/DAGENT_HOST_DIAGNOSTICS']
cmd+=['/I'+str(kits/'Include'/version/x) for x in ['ucrt','shared','um']]
cmd+=[str(r/'native/agent-host/AgentHost.cpp'),'/Fo'+str(o/'AgentHost.obj'),'/Fe'+str(o/'AgentHostNative.dll'),'/link','/DEF:'+str(r/'native/agent-host/agent-host.def'),'/LIBPATH:'+str(msvc/'lib/x64')]
cmd+=['/LIBPATH:'+str(kits/'Lib'/version/x/'x64') for x in ['ucrt','um']]
subprocess.run(cmd,cwd=o,check=True)
test=[x for x in cmd[:cmd.index('/link')] if x!='/LD' and not x.startswith('/Fo') and not x.startswith('/Fe') and not x.endswith('AgentHost.cpp')]
test += [str(r/'native/agent-host/NativeContractTest.cpp'),'/Fo'+str(o/'NativeContractTest.obj'),'/Fe'+str(o/'NativeContractTest.exe'),'/link']+[x for x in cmd[cmd.index('/link')+1:] if x.startswith('/LIBPATH:')]
subprocess.run(test,cwd=o,check=True);subprocess.run([str(o/'NativeContractTest.exe')],check=True)

manifest='<?xml version="1.0" encoding="UTF-8"?><bundle xmlns="http://v8.1c.ru/8.2/addin/bundle"><component os="Windows" path="AgentHostNative.dll" type="native" arch="x86_64"/></bundle>'
archive=o/'agent-host-native-windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('manifest.xml',manifest);z.write(o/'AgentHostNative.dll','AgentHostNative.dll');z.write(r/'native/agent-host/include/LICENSE','SDK-LICENSE');z.write(r/'LICENSE','LICENSE')
report={'protocol':2,'features':['screenshot_png'],'platform':'windows-x64','artifact':str(archive),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'diagnostics':a.diagnostics,'contract_test':'passed','dll_sha256':hashlib.sha256((o/'AgentHostNative.dll').read_bytes()).hexdigest()}
(o/'native-build.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
