"""Run native EDT regression tests against standalone Bridge JARs, without old CodePilot."""
from pathlib import Path
from bridge_provenance import VerificationRun
import argparse,json,os,shutil,subprocess,sys,zipfile,xml.etree.ElementTree as ET
if sys.flags.optimize:raise RuntimeError("Release verification requires Python without -O/PYTHONOPTIMIZE")
root=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--build',required=True);p.add_argument('--java-home',default=os.environ.get('JAVA_HOME'));a=p.parse_args()
build=Path(a.build).resolve();meta=json.loads((build/'build.json').read_text(encoding='utf-8'));java=Path(a.java_home) if a.java_home else None
junit=Path(os.environ.get('JUNIT_JAR',str(Path.home()/'.p2/pool/plugins/org.junit_4.13.2.v20230809-1000.jar')))
hamcrest=Path(os.environ.get('HAMCREST_JAR',str(Path.home()/'.m2/repository/org/hamcrest/hamcrest/2.2/hamcrest-2.2.jar')))
with VerificationRun(root,build,meta) as verification:
 with verification.stage("java"):
  if java is None:raise RuntimeError("Set JAVA_HOME to JDK 17+")
  if not junit.is_file() or not hamcrest.is_file():raise RuntimeError("Set JUNIT_JAR and HAMCREST_JAR")
  cp=';'.join(meta['artifacts']+meta['classpath']+[str(junit),str(hamcrest)])
  base=root/'bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core'
  sources=sorted((base/'edt').rglob('*Test.java'))
  sources+=[base/'tools/ToolLoggerSensitiveResultTest.java',base/'tools/ToolExecutionServiceSensitiveTraceTest.java',base/'evaluation/trace/tests/AbstractTraceTest.java']
  sources+=[base/'tools/ConnectInfobaseToolTest.java',base/'qa/QaRunClientParamsTest.java',base/'mcp/host/transport/McpHostSseAuthorizationTest.java',base/'mcp/host/transport/McpBridgeSurfaceTest.java']
  # Compile every explicitly enabled contract; old removed OAuth/LLM tests stay excluded.
  for name in (root/'agent-bridge/test-suite.txt').read_text(encoding='utf-8').splitlines():
   candidate=root/'bundles/com.codepilot1c.core.tests/src'/Path(name.replace('.','/')+'.java')
   if not candidate.is_file():raise RuntimeError('Missing regression source: '+name)
   if candidate not in sources:sources.append(candidate)
  classes=build/'test-classes';classes.mkdir(exist_ok=True)
  quote=lambda s:'"'+str(s).replace('\\','/')+'"'
  args=build/'test-javac.args';args.write_text('\n'.join(['--release','17','-encoding','UTF-8','-classpath',quote(cp),'-d',quote(classes)]+[quote(x) for x in sources]),encoding='utf-8')
  subprocess.run([str(java/'bin/javac.exe'),'@'+str(args)],check=True)
  tests=(root/'agent-bridge/test-suite.txt').read_text(encoding='utf-8').splitlines()
  args=build/'test-java.args';args.write_text('\n'.join(['-Djavax.xml.parsers.DocumentBuilderFactory=com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl','-Dcore.bundle.path='+quote(meta['artifacts'][0]),'-classpath',quote(str(classes)+';'+cp),'org.junit.runner.JUnitCore']+tests),encoding='utf-8')
  subprocess.run([str(java/'bin/java.exe'),'@'+str(args)],check=True)

 with verification.stage("python"):
  for script in ("direct-testclient-worker-test.py","direct-testclient-table-test.py","direct-testclient-controls-test.py","direct-testclient-dialogs-test.py"):
   subprocess.run([sys.executable,str(root/"tools/tests"/script)],check=True)

 with verification.stage("product_boundary"):
  # Product boundary checks inspect the actual distributable artifacts.
  for artifact in meta['artifacts']:
   with zipfile.ZipFile(artifact) as z:
    names=z.namelist()
    assert not any("__pycache__" in name or name.lower().endswith((".pyc",".pyo")) for name in names), "Generated Python cache must not be shipped"
    banned=['/provider/','/backend/','/mcp/McpServerManager','/mcp/host/llm/','/agent/langgraph/','/memory/','/skills/','/views/ChatView','lib/']
    for name in names:
     if any(x in name for x in banned):raise AssertionError('Excluded component in artifact: '+name)
    if '.core_' in artifact:
     for script in ('run-vanessa-debug.ps1','managed-vanessa-core.ps1'):
      assert 'scripts/'+script in names
      assert z.read('scripts/'+script)==(root/'tools'/script).read_bytes()==(root/'bundles/com.codepilot1c.core/scripts'/script).read_bytes(), 'Managed controller copies must match the distributable'
     catalog=json.loads(z.read('com/codepilot1c/core/qa/steps_catalog.json'))
     assert catalog, 'QA steps catalog must be shipped'
    manifest=z.read('META-INF/MANIFEST.MF').decode();assert 'EDT Agent Bridge' in manifest
    xml=ET.fromstring(z.read('plugin.xml'))
    if '.ui_' in artifact:
     pages=xml.findall("./extension[@point='org.eclipse.ui.preferencePages']/page")
     assert len(pages)==1 and pages[0].attrib['name']=='EDT Agent Bridge'
     assert xml.find("./extension[@point='org.eclipse.ui.views']") is None
  print('PASS: standalone product boundary and account-route removal')

 with verification.stage("osgi_linkage"):
  subprocess.run([sys.executable,str(root/"tools/check-agent-bridge-linkage.py"),"--build",str(build),"--java-home",str(java)],check=True)

 with verification.stage("provenance"):
  subprocess.run([sys.executable,str(root/"tools/tests/bridge-provenance-test.py")],check=True)
  subprocess.run([sys.executable,str(root/"tools/tests/bridge-package-test.py")],check=True)
 if os.name != "nt":raise RuntimeError("Windows PowerShell contracts are mandatory for release verification")
 shell7=shutil.which("pwsh")
 if not shell7:raise RuntimeError("PowerShell7 is required for managed runner contract tests")
 shell5=Path(os.environ["SystemRoot"])/"System32/WindowsPowerShell/v1.0/powershell.exe"
 environment=dict(os.environ,POWERSHELL_TELEMETRY_OPTOUT="1",POWERSHELL_UPDATECHECK="Off")
 for stage,shell,script in (("managed_powershell",shell7,"test-managed-controller.ps1"),("startup_powershell",str(shell5),"test-startup-consent.ps1")):
  with verification.stage(stage):
   target=root/"tools"/script
   if not target.is_file():raise RuntimeError("Mandatory test source is absent; resolve deletion or quarantine before testing: "+script)
   subprocess.run([shell,"-NoProfile","-NonInteractive","-File",str(target)],check=True,env=environment)
