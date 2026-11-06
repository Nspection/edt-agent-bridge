"""Run native EDT regression tests against standalone Bridge JARs, without old CodePilot."""
from pathlib import Path
import argparse,json,os,subprocess,zipfile,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--build',required=True);p.add_argument('--java-home',default=os.environ.get('JAVA_HOME'));a=p.parse_args()
if not a.java_home:p.error('Set JAVA_HOME to JDK 17+')
build=Path(a.build).resolve();meta=json.loads((build/'build.json').read_text(encoding='utf-8'));java=Path(a.java_home)
junit=Path(os.environ.get('JUNIT_JAR',str(Path.home()/'.p2/pool/plugins/org.junit_4.13.2.v20230809-1000.jar')))
hamcrest=Path(os.environ.get('HAMCREST_JAR',str(Path.home()/'.m2/repository/org/hamcrest/hamcrest/2.2/hamcrest-2.2.jar')))
if not junit.is_file() or not hamcrest.is_file():raise RuntimeError('Set JUNIT_JAR and HAMCREST_JAR')
cp=';'.join(meta['artifacts']+meta['classpath']+[str(junit),str(hamcrest)])
base=root/'bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core'
sources=sorted((base/'edt').rglob('*Test.java'))
sources+=[base/'tools/ConnectInfobaseToolTest.java',base/'qa/QaRunClientParamsTest.java',base/'mcp/host/transport/McpHostSseAuthorizationTest.java',base/'mcp/host/transport/McpBridgeSurfaceTest.java']
classes=build/'test-classes';classes.mkdir(exist_ok=True)
quote=lambda s:'"'+str(s).replace('\\','/')+'"'
args=build/'test-javac.args';args.write_text('\n'.join(['--release','17','-encoding','UTF-8','-classpath',quote(cp),'-d',quote(classes)]+[quote(x) for x in sources]),encoding='utf-8')
subprocess.run([str(java/'bin/javac.exe'),'@'+str(args)],check=True)
tests=(root/'agent-bridge/test-suite.txt').read_text(encoding='utf-8').splitlines()
args=build/'test-java.args';args.write_text('\n'.join(['-Djavax.xml.parsers.DocumentBuilderFactory=com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl','-classpath',quote(str(classes)+';'+cp),'org.junit.runner.JUnitCore']+tests),encoding='utf-8')
subprocess.run([str(java/'bin/java.exe'),'@'+str(args)],check=True)
# Product boundary checks inspect the actual distributable artifacts.
for artifact in meta['artifacts']:
 with zipfile.ZipFile(artifact) as z:
  names=z.namelist()
  banned=['/provider/','/backend/','/mcp/McpServerManager','/mcp/host/llm/','/agent/langgraph/','/memory/','/skills/','/views/ChatView','lib/']
  for name in names:
   if any(x in name for x in banned):raise AssertionError('Excluded component in artifact: '+name)
  if '.core_' in artifact:assert 'scripts/run-vanessa-debug.ps1' in names
  manifest=z.read('META-INF/MANIFEST.MF').decode();assert 'EDT Agent Bridge' in manifest
  xml=ET.fromstring(z.read('plugin.xml'))
  if '.ui_' in artifact:
   pages=xml.findall("./extension[@point='org.eclipse.ui.preferencePages']/page")
   assert len(pages)==1 and pages[0].attrib['name']=='EDT Agent Bridge'
   assert xml.find("./extension[@point='org.eclipse.ui.views']") is None
print('PASS: standalone product boundary and account-route removal')
