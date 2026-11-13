"""Verify immediate own-popup composition without using or switching the input desktop."""
from pathlib import Path
import argparse,json,subprocess,threading,time
from bridge_provenance import require_build,sha256
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
parser=argparse.ArgumentParser(description='Actual Native DLL popup capture on an owned unselected desktop; never opens a user-desktop window.');parser.add_argument('--build',required=True);parser.add_argument('--native-build',required=True);parser.add_argument('--output',required=True);parser.add_argument('--java-home',required=True);args=parser.parse_args();r=Path(__file__).resolve().parent.parent;out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True);native=Path(args.native_build).resolve();meta=json.loads((Path(args.build)/'build.json').read_text());cp=';'.join(meta['artifacts']+meta['classpath']);require_build(r,meta);nativeMeta=json.loads((native/'native-build.json').read_text());assert sha256(native/'AgentHostNative.dll')==nativeMeta['dll_sha256'];assert sha256(native/'agent-host-native-windows-x64.zip')==nativeMeta['sha256']
received=[]
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_POST(self):
  assert self.path=='/snapshot' and self.headers['Authorization']=='Bearer '+'0'*64
  data=self.rfile.read(int(self.headers['Content-Length']));capture=json.loads(self.headers['X-Bridge-Capture']);i=len(received);(out/f'probe-{i}.png').write_bytes(data);received.append(capture)
  response=json.dumps({'snapshot_id':str(i)}).encode();self.send_response(200);self.send_header('Content-Length',str(len(response)));self.end_headers();self.wfile.write(response)
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
source=out/'DesktopProbe.java';source.write_text('''import com.codepilot1c.core.edt.development.IsolatedDesktopProcess;
import java.util.concurrent.TimeUnit;
public class DesktopProbe {
 interface DesktopApi extends com.sun.jna.win32.StdCallLibrary {com.sun.jna.Pointer GetThreadDesktop(int thread);}
 static final DesktopApi USER=com.sun.jna.Native.load("user32",DesktopApi.class);
 static com.sun.jna.Pointer desktop(){return USER.GetThreadDesktop(com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId());}
 public static void main(String[] args)throws Exception {
  var original=desktop();for(int i=0;i<30;i++)IsolatedDesktopProcess.verifyAvailable();if(!original.equals(desktop()))throw new AssertionError("Caller desktop changed");
  Process child=IsolatedDesktopProcess.start(new ProcessBuilder(java.util.Arrays.copyOfRange(args,1,args.length)));
  if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();throw new AssertionError("Owned probe timed out");}
  if(child.exitValue()!=0)throw new AssertionError("Owned probe exit "+child.exitValue());
  var image=javax.imageio.ImageIO.read(new java.io.File(args[0]));
  for(int rgb:new int[]{0xdc141e,0x14d21e,0x141edc}){int count=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)&0xffffff)==rgb)count++;if(count<3000)throw new AssertionError("Popup pixel row absent: "+count);}
  for(int i=0;i<30;i++)IsolatedDesktopProcess.verifyAvailable();if(!original.equals(desktop()))throw new AssertionError("Caller desktop changed");
 }
}''',encoding='utf-8')
java=Path(args.java_home)/'bin';quote=lambda x:'"'+str(x).replace('\\','/')+'"'
compile=out/'probe-javac.args';compile.write_text('\n'.join(['-classpath',quote(cp),'-d',quote(out),quote(source)]),encoding='utf-8');subprocess.run([str(java/'javac.exe'),'@'+str(compile)],check=True)
args=out/'probe-java.args';args.write_text('\n'.join(['-classpath',quote(str(out)+';'+cp),'DesktopProbe',quote(out/'probe-0.png'),quote(native/'NativeContractTest.exe'),'--desktop-probe',quote(native/'AgentHostNative.dll'),str(server.server_port)]),encoding='utf-8')
try:subprocess.run([str(java/'java.exe'),'@'+str(args)],check=True,timeout=30)
finally:server.shutdown()
assert len(received)==2,received
assert received[0]['armed'] and received[0]['window_count']>=2 and received[0]['popup_count']==received[0]['window_count']-1,received
assert not received[1]['armed'] and received[1]['window_count']==received[0]['window_count']-1,received
report={'passed':True,'actual_dll':True,'dll_sha256':nativeMeta['dll_sha256'],'artifact_sha256':meta['artifact_sha256'],'isolated_desktop':True,'desktop_probes':60,'caller_desktop_unchanged':True,'popup_destroyed_before_upload':True,'popup_rgb_rows_verified':3,'captures':received};(out/'probe-result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
