"""Verify/install the local EDT Agent Bridge p2 feature. Verify-only is the default.
Replaces only the existing CodePilot/Bridge feature; no SDK download is required.
"""
from pathlib import Path
import argparse,os,json,subprocess,gzip,xml.etree.ElementTree as ET,urllib.parse,urllib.request,hashlib,shutil
p=argparse.ArgumentParser();p.add_argument('--build',required=True);p.add_argument('--edt-home',default=os.environ.get('CODEPILOT_EDT_HOME'));p.add_argument('--java-home',default=os.environ.get('JAVA_HOME'));p.add_argument('--apply',action='store_true');p.add_argument('--rollback',action='store_true');p.add_argument('--resume',action='store_true');a=p.parse_args()
if not a.edt_home or not a.java_home:p.error('Set CODEPILOT_EDT_HOME and JAVA_HOME')
b=Path(a.build).resolve();edt=Path(a.edt_home).resolve();java=Path(a.java_home);m=json.loads((b/'build.json').read_text(encoding='utf-8'))
if not (b/'update-site/content.jar').is_file():p.error('Package the update site before installation')
# Parse Java properties, including escaped drive colons and continued lines.
raw=(edt/'configuration/config.ini').read_text(encoding='utf-8-sig').replace('\\\n','')
props={}
for line in raw.splitlines():
 if '=' in line and not line.startswith(('#','!')):
  k,v=line.split('=',1);props[k]=v.replace('\\:',':').replace('\\ ',' ').replace('\\\\','\\')
profile=props.get('eclipse.p2.profile');area=props.get('eclipse.p2.data.area','')
if not profile or not area.startswith('file:'):p.error('Cannot determine existing shared p2 profile; no installation changes made')
shared=Path(urllib.request.url2pathname(urllib.parse.urlsplit(area).path)).resolve()
profile_dir=shared/'org.eclipse.equinox.p2.engine/profileRegistry'/(profile+'.profile')
profiles=sorted(profile_dir.glob('*.profile.gz'),key=lambda x:int(x.name.split('.')[0]))
if not profiles:p.error('Existing p2 profile is not readable')
with gzip.open(profiles[-1]) as f:xml=ET.parse(f).getroot()
units=xml.find('units');old=[x.get('version') for x in units if x.get('id')=='com.codepilot1c.feature.feature.group']
if len(old)>1:p.error('Multiple CodePilot features are installed; review the profile first')
launcher=next(x for x in m['classpath'] if Path(x).name.startswith('org.eclipse.equinox.launcher_'))
cmd=[str(java/'bin/java.exe'),'-jar',launcher,'-install',str(edt),'-configuration',str(b/'publisher-configuration'),'-data',str(b/'director-workspace'),'-nosplash','-application','org.eclipse.equinox.p2.director','-repository',(b/'update-site').as_uri(),'-destination',str(edt),'-shared',str(shared),'-bundlepool',str(Path(props.get('eclipse.p2.cache',str(shared/'pool')))),'-profile',profile]
state_path=b/'install-state.json'
def equivalent_profile(previous,current):
 with gzip.open(previous) as f:left=ET.parse(f).getroot()
 with gzip.open(current) as f:right=ET.parse(f).getroot()
 for node in [left,right]:
  node.attrib.pop('timestamp',None)
  for prop in node.findall('./properties/property'):
   if prop.get('name')=='org.eclipse.equinox.p2.roaming':prop.set('value','P2_METADATA')
 return ET.tostring(left)==ET.tostring(right)

if a.rollback:
 if not state_path.is_file():p.error('No previous installation state for this build')
 state=json.loads(state_path.read_text());
 if state['edt_home']!=str(edt) or state['profile']!=profile:p.error('Rollback target does not match recorded installation')
 cmd+=['-revert',state['previous_profile_timestamp']]
else:
 if old:cmd+=['-uninstallIU','com.codepilot1c.feature.feature.group/'+old[0]]
 cmd+=['-installIU','com.codepilot1c.feature.feature.group/'+m['version'],'-roaming']
if not a.apply:cmd+=['-verifyOnly']
if a.apply:
 if os.name!='nt':p.error('This installer currently verifies Windows EDT only')
 running=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',"@(Get-Process -Name 1cedt,1cedtstart -ErrorAction SilentlyContinue).Count"],capture_output=True,text=True,check=True)
 if int(running.stdout.strip() or '0'):p.error('Save work and close EDT and EDT Start before applying')
 if not a.rollback:
  info=edt/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
  if state_path.exists():
   if not a.resume:p.error('An installation record exists; inspect state and use --resume only for an unchanged prepared target')
   state=json.loads(state_path.read_text())
   if state['state']!='prepared' or state['target_version']!=m['version'] or state['edt_home']!=str(edt) or not equivalent_profile(profile_dir/(state['previous_profile_timestamp']+'.profile.gz'),profiles[-1]) or state['previous_bundles_info_sha256']!=hashlib.sha256(info.read_bytes()).hexdigest():p.error('Prepared installation target changed; no retry was performed')
  else:
   backup=b/'install-backup';backup.mkdir(exist_ok=False);shutil.copyfile(info,backup/'bundles.info')
   state={'edt_home':str(edt),'profile':profile,'previous_profile_timestamp':profiles[-1].name.split('.')[0],'previous_feature':old[0] if old else None,'target_version':m['version'],'state':'prepared','previous_bundles_info_sha256':hashlib.sha256(info.read_bytes()).hexdigest()}
   state_path.write_text(json.dumps(state,indent=2),encoding='utf-8')

log_path=b/('director-rollback.log' if a.rollback else 'director-install.log' if a.apply else 'director-verify.log')
with log_path.open('wb') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=900)
if r.returncode:
 print(log_path.read_bytes().decode('utf-8',errors='replace')[-12000:]);raise SystemExit(r.returncode)
if a.apply:
 if a.rollback:
  info=edt/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
  backup=b/'install-backup/bundles.info'
  if hashlib.sha256(backup.read_bytes()).hexdigest()!=state['previous_bundles_info_sha256']:raise RuntimeError('Rollback bundles.info backup changed')
  shutil.copyfile(backup,info);state['state']='rolled_back'
 else:
  info=(edt/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info').read_text(encoding='utf-8-sig')
  for bundle in ['com.codepilot1c.core','com.codepilot1c.ui']:
   if bundle+','+m['version']+',' not in info:raise RuntimeError('p2 returned success but installed bundle version is unexpected')
  state['state']='installed'
 state_path.write_text(json.dumps(state,indent=2),encoding='utf-8')
print(json.dumps({'action':'rollback' if a.rollback else 'install','applied':a.apply,'verified':True,'version':m['version'],'log':str(log_path)}))
