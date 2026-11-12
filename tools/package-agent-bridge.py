"""Create a standalone p2 update site using Eclipse's official FeaturesAndBundlesPublisher."""
from pathlib import Path
from bridge_provenance import require_verified, sha256
from bridge_package import commit_package, verify_site
import uuid
import argparse,os,json,zipfile,shutil,subprocess,urllib.parse,urllib.request,xml.etree.ElementTree as ET

def main():
 if not __debug__:raise RuntimeError("Optimized Python is not supported for release tools")
 root=Path(__file__).resolve().parent.parent
 p=argparse.ArgumentParser();p.add_argument('--build',required=True);p.add_argument('--edt-home',default=os.environ.get('CODEPILOT_EDT_HOME'));p.add_argument('--java-home',default=os.environ.get('JAVA_HOME'));a=p.parse_args()
 if not a.edt_home or not a.java_home:p.error('Set CODEPILOT_EDT_HOME and JAVA_HOME')
 build=Path(a.build).resolve();edt=Path(a.edt_home).resolve();java=Path(a.java_home);meta=json.loads((build/'build.json').read_text(encoding='utf-8'));version=meta['version']
 verification=require_verified(root,build,meta)
 generation=build/'packages'/('package-'+uuid.uuid4().hex)
 generation.mkdir(parents=True,exist_ok=False)
 source=generation/'p2-source';site=generation/'update-site';config=generation/'publisher-configuration'
 for directory in [source/'plugins',source/'features',site,config/'org.eclipse.equinox.simpleconfigurator']:directory.mkdir(parents=True,exist_ok=True)
 for artifact in meta['artifacts']:shutil.copyfile(artifact,source/'plugins'/Path(artifact).name)
 feature=ET.Element('feature',{'id':'com.codepilot1c.feature','label':'EDT Agent Bridge','version':version,'provider-name':'EDT Agent Bridge contributors; original CodePilot authors'})
 ET.SubElement(feature,'description').text='Native EDT tools, debugging and managed Vanessa automation over local MCP. No embedded chat or LLM accounts.'
 ET.SubElement(feature,'copyright').text='Original CodePilot1C authors and EDT Agent Bridge contributors. See THIRD_PARTY_NOTICES.md for source attribution.'
 ET.SubElement(feature,'license',{'url':'https://www.gnu.org/licenses/agpl-3.0.html'}).text=(root/'LICENSE').read_text()
 for name in ['com.codepilot1c.core','com.codepilot1c.ui']:ET.SubElement(feature,'plugin',{'id':name,'version':version,'download-size':'0','install-size':'0','unpack':'false'})
 with zipfile.ZipFile(source/'features'/('com.codepilot1c.feature_'+version+'.jar'),'w',zipfile.ZIP_DEFLATED) as z:
  z.writestr('feature.xml',ET.tostring(feature,encoding='utf-8',xml_declaration=True));z.write(root/'LICENSE','LICENSE');z.write(root/'THIRD_PARTY_NOTICES.md','THIRD_PARTY_NOTICES.md')
 entries={}
 info=edt/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
 normalized=[]
 for line in info.read_text(encoding='utf-8-sig').splitlines():
  if line.startswith('#'):continue
  fields=line.split(',')
  if len(fields)<3:continue
  location=fields[2]
  if location.startswith('file:'):path=Path(urllib.request.url2pathname(urllib.parse.urlsplit(location).path))
  else:path=edt/location
  path=path.resolve()
  if not path.exists():raise RuntimeError('Installed target bundle location is missing: '+fields[0])
  entries[fields[0]]=path
  fields[2]=path.as_uri()
  if not fields[0].startswith(('com._1c.','com.e1c.','com.codepilot1c.','tormozit.','com.company1c.')):normalized.append(','.join(fields))
 # Absolute target locations also work after p2 rewrites the host bundle table to relative paths.
 (config/'org.eclipse.equinox.simpleconfigurator/bundles.info').write_text('\n'.join(normalized)+'\n',encoding='utf-8')

 escape=lambda s:s.replace('\\','\\\\').replace(':','\\:')
 settings={'osgi.bundles':'reference:'+entries['org.eclipse.equinox.simpleconfigurator'].as_uri()+'@1:start','osgi.framework':entries['org.eclipse.osgi'].as_uri(),'osgi.bundles.defaultStartLevel':'4','osgi.configuration.cascaded':'false','eclipse.application':'org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher'}
 (config/'config.ini').write_text('\n'.join(k+'='+escape(v) for k,v in settings.items())+'\n',encoding='utf-8')
 cmd=[str(java/'bin/java.exe'),'-jar',str(entries['org.eclipse.equinox.launcher']),'-install',str(edt),'-configuration',str(config),'-data',str(generation/'publisher-workspace'),'-nosplash','-clean','-application','org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher','-source',str(source),'-metadataRepository',site.as_uri(),'-artifactRepository',site.as_uri(),'-publishArtifacts','-compress']
 with (generation/'publisher.log').open('w',encoding='utf-8') as log:result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=180)
 if result.returncode or not (site/'content.jar').is_file():print((generation/'publisher.log').read_text(encoding='utf-8')[-12000:]);raise SystemExit(result.returncode or 1)
 # Make the feature visible when EDT groups available software by category.
 category=ET.Element('site')
 item=ET.SubElement(category,'feature',{'id':'com.codepilot1c.feature','version':version,'url':'features/com.codepilot1c.feature_'+version+'.jar'})
 ET.SubElement(item,'category',{'name':'edt-agent-bridge'})
 ET.SubElement(category,'category-def',{'name':'edt-agent-bridge','label':'EDT Agent Bridge'})
 category_file=generation/'category.xml';category_file.write_bytes(ET.tostring(category,encoding='utf-8',xml_declaration=True))
 category_cmd=cmd[:cmd.index('-application')]+['-application','org.eclipse.equinox.p2.publisher.CategoryPublisher','-metadataRepository',site.as_uri(),'-categoryDefinition',category_file.as_uri(),'-compress']
 with (generation/'category-publisher.log').open('w',encoding='utf-8') as log:category_result=subprocess.run(category_cmd,stdout=log,stderr=subprocess.STDOUT,timeout=180)
 if category_result.returncode:raise RuntimeError('p2 CategoryPublisher failed; inspect category-publisher.log')
 # Repository display names must not expose local filesystem paths.
 import re
 for filename,entry in [('content.jar','content.xml'),('artifacts.jar','artifacts.xml')]:
  repository=site/filename
  with zipfile.ZipFile(repository) as z:payload=z.read(entry)
  payload,count=re.subn(rb'(<repository\b[^>]*?\bname=)([\"\'])(.*?)\2',lambda m:m[1]+m[2]+b'EDT Agent Bridge'+m[2],payload,count=1)
  if count!=1:raise RuntimeError('Cannot set public p2 repository name')
  # CategoryPublisher defaults to a local category URI as the IU qualifier.
  payload=re.sub(rb'file:[^\"\'<>\s]*category\.xml\.edt-agent-bridge',b'category.edt-agent-bridge',payload)
  parsed=ET.fromstring(payload)
  if parsed.get('name')!='EDT Agent Bridge':raise RuntimeError('p2 repository name verification failed')
  with zipfile.ZipFile(repository,'w',zipfile.ZIP_DEFLATED) as z:z.writestr(entry,payload)

 feature_hash=sha256(source/'features'/('com.codepilot1c.feature_'+version+'.jar'))
 files=verify_site(site,version,meta['artifact_sha256'],feature_hash)
 archive=generation/('edt-agent-bridge-'+version+'-update-site.zip')
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for name in sorted(files):z.write(site/name,name)
 commit_package(root,build,meta,generation,feature_hash)
 print(json.dumps({'update_site':str(site),'archive':str(archive),'version':version}))

if __name__ == "__main__":
 main()
