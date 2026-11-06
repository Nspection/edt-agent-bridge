"""Two-phase native recovery acceptance. Run prepare, restart only the isolated EDT, then verify.
All fixtures must be in an explicit .runs/creation-audit root, preferably outside Git.
Never uses an existing user infobase; credentials are not used by this audit.
"""
from pathlib import Path
import argparse,json,time,uuid,hashlib,re,os,shutil,xml.etree.ElementTree as ET
from importlib.machinery import SourceFileLoader

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('phase',choices=['prepare','verify']);p.add_argument('--output',required=True);p.add_argument('--audit-root',required=True)
 p.add_argument('--dt',required=True);p.add_argument('--config',required=True);p.add_argument('--server',required=True);p.add_argument('--workspace',required=True)
 p.add_argument('--runtime-version',default='8.5.1.1522');p.add_argument('--apply',action='store_true')
 a=p.parse_args();out=Path(a.output).resolve();audit=Path(a.audit_root).resolve();dt=Path(a.dt).resolve();workspace=Path(a.workspace).resolve()
 if tuple(audit.parts[-2:])!=('.runs','creation-audit') or out==audit or not out.is_relative_to(audit):p.error('Explicit disposable .runs/creation-audit root required')
 if not a.apply:print(json.dumps({'output':str(out),'phase':a.phase,'apply_required':True}));return
 root=Path(__file__).resolve().parent.parent;Client=SourceFileLoader('creation_audit_client',str(root/'tools/audit-edt-creation.py')).load_module().Client
 for attempt in range(120):
  try:
   c=Client(a.config,a.server)
   if {'edt_window','edt_diagnostics','edt_project_from_application','edt_operation_status'}<={t['name'] for t in c.rpc('tools/list',{})['tools']}:break
  except Exception:pass
  time.sleep(.5)
 else:raise RuntimeError('Isolated Bridge registry did not become ready')
 title=c.call('edt_window',{'action':'state'})['window']['title']
 if 'EDT-Agent-Bridge-Dev' not in title:raise RuntimeError('Use only the isolated development EDT workspace')
 report_file=out/'report.json'
 def save():report_file.write_text(json.dumps(report,indent=2),encoding='utf-8')
 def check(key,value):
  report['cases'][key]=bool(value);save()
  if not value:raise AssertionError(key)
 def job(v):
  for i in range(600):
   s=c.call('edt_operation_status',{'job_id':v.get('job_id') or v.get('jobId')})
   if s['state']=='DONE':return s['result']
   if s['state']=='FAILED':raise RuntimeError(str(s))
   time.sleep(.5)
  raise TimeoutError()
 def apply(tool,request):
  plan=c.call(tool,request)
  return job(c.call(tool,{**request,'dry_run':False,'expected_preview_sha256':plan['preview_sha256']}))
 def rows(database):
  text=(Path.home()/'AppData/Roaming/1C/1CEStart/ibases.v8i').read_text(encoding='utf-8-sig');result=[]
  for block in re.split(r'(?m)(?=^\[)',text):
   file=re.search(r'File="([^"]+)"',block)
   if file and Path(file[1]).resolve()==database:
    version=re.search(r'(?m)^Version=(.*)$',block);identifier=re.search(r'(?m)^ID=(.*)$',block)
    result.append({'version':version[1].strip() if version else '', 'id':identifier[1].strip() if identifier else ''})
  return result
 if a.phase=='prepare':
  out.mkdir(parents=True,exist_ok=False);source='BridgeCreationRecovery'+uuid.uuid4().hex[:8];project=out/'project';database=out/'RecoveryDatabase'
  report={'source_project':source,'project_path':str(project),'database_path':str(database),'cases':{},'phase':'preparing'};save()
  # Copy only the closed empty fixture DB. No user data or open DB files are copied.
  fixture=dt.parent/'fixture-database/1Cv8.1CD'
  if not fixture.is_file():raise RuntimeError('Expected a closed disposable fixture-database beside the test DT')
  database.mkdir();shutil.copy2(fixture,database/'1Cv8.1CD')
  job(c.call('edt_project_create',{'project':source,'project_path':str(project),'configuration_name':'RecoveryFixture','version':'8.5.1'}))
  for i in range(180):
   if (project/'src/Configuration/Configuration.mdo').is_file():break
   time.sleep(.5)
  cfg=ET.parse(project/'src/Configuration/Configuration.mdo').getroot()
  (project/'.edt-bridge-creation-audit.json').write_text(json.dumps({'purpose':'edt-bridge-creation-coverage','project':source,'configuration_uuid':cfg.attrib['uuid']}),encoding='utf-8')
  request={'project':source,'database_path':str(database),'dt_path':str(dt),'runtime_version':a.runtime_version,'restore_existing':True,'update_from_project':False}
  for i in range(180):
   try:c.call('edt_infobase_provision',request);break
   except RuntimeError as e:
    if 'READY' not in str(e) and 'corredponding DT' not in str(e):raise
    time.sleep(.5)
  report['initial_bind']=c.call('connect_infobase',{'project_name':source,'database_path':str(database),'kind':'file','set_primary':True});before=rows(database);report['registered_before']=before;save()
  request['database_path']=str(database).upper().replace('\\','/')
  report['restore']=apply('edt_infobase_provision',request);after=rows(database);report['registered_after']=after
  check('existing_registration_version_persisted',len(before)==1 and len(after)==1 and after[0]['version']==a.runtime_version and before[0]['id']==after[0]['id'])
  marker=workspace/'.metadata/.plugins/com.codepilot1c.core/infobase-lifecycle'/(hashlib.sha256(('file:'+str(database).lower()).encode('utf-8')).hexdigest()+'.full-required')
  report['marker_path']=str(marker);check('canonical_pending_marker_exists',marker.is_file())
  report['snapshot']=apply('edt_project_from_application',{'mode':'compare','project':source,'database_path':str(database),'runtime_version':a.runtime_version});save()
  sid=report['snapshot']['comparison']['session_id']
  for i in range(180):
   status=c.call('edt_compare',{'action':'status','session_id':sid})
   if status.get('status')=='COMPARISON_PROCESS_FINISHED':break
   time.sleep(.5)
  check('snapshot_ready_before_restart',status.get('status')=='COMPARISON_PROCESS_FINISHED')
  report['phase']='restart_required';save();print('Prepared: version persisted, canonical marker verified, snapshot ready; restart only dev EDT')
 else:
  report=json.loads(report_file.read_text(encoding='utf-8'));source=report['source_project'];database=Path(report['database_path']);project=Path(report['project_path'])
  if report['phase']!='restart_required':raise RuntimeError('Prepare must finish before restart verification')
  for i in range(180):
   try:
    c.call('edt_creation_defaults',{'project':source,'class_name':'Catalog','fields':['codeLength']});break
   except RuntimeError:time.sleep(.5)
  else:raise RuntimeError('Source BM context did not become ready after restart')
  check('registration_version_survived_restart',rows(database)[0]['version']==a.runtime_version)
  report['update']=job(c.call('edt_diagnostics',{'command':'update_infobase','project':source,'async':True}));check('deferred_full_reload_after_restart',report['update'].get('updated') and report['update'].get('update_mode')=='full')
  check('pending_marker_cleared_after_equal',not Path(report['marker_path']).exists())
  report['snapshot_cleanup']=c.call('edt_project_from_application',{'action':'cleanup','import_id':report['snapshot']['import_id']});check('snapshot_cleanup_after_restart',report['snapshot_cleanup'].get('snapshot_removed'))
  again=c.call('edt_project_from_application',{'action':'cleanup','import_id':report['snapshot']['import_id']});check('snapshot_cleanup_idempotent',again.get('snapshot_removed'))
  report['source_cleanup']=job(c.call('edt_creation_audit_cleanup',{'project':source,'expected_project_path':str(project),'dry_run':False}));check('source_project_removed',report['source_cleanup'].get('removed'))
  report['phase']='passed';save();print(json.dumps({'status':'passed','cases':report['cases']}))
if __name__=='__main__':main()
