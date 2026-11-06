"""Native MCP infobase lifecycle acceptance in explicitly disposable fixtures.
Requires --apply, an existing small DT fixture and a running EDT 2026.1 Bridge.
Never selects a user's database/project. Source DT is read, never overwritten.
Reports are local; passwords are not used or logged by this fixture audit.
"""
from pathlib import Path
import argparse,importlib.util,json,time,uuid,xml.etree.ElementTree as ET,sys

def main():
 p=argparse.ArgumentParser();p.add_argument('--dt',required=True);p.add_argument('--runtime-version',required=True)
 p.add_argument('--version',default='8.5.1');p.add_argument('--config',default=str(Path.home()/'.codex/config.toml'));p.add_argument('--server',default='edt_bridge');p.add_argument('--apply',action='store_true');p.add_argument('--resume-after-restore',action='store_true');p.add_argument('--output',required=True)
 a=p.parse_args();out=Path(a.output).resolve();dt=Path(a.dt).resolve();root=Path(__file__).resolve().parent.parent
 if not out.is_relative_to(root/'.runs/creation-audit') or not dt.is_file():p.error('Use a disposable output inside .runs/creation-audit and an existing small DT fixture')
 if not a.apply:print(json.dumps({'output':str(out),'dt_source':str(dt),'apply_required':True}));return
 out.mkdir(parents=True,exist_ok=a.resume_after_restore)
 spec=importlib.util.spec_from_file_location('creation_audit_client',root/'tools/audit-edt-creation.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 required={'edt_infobase_provision','edt_project_from_application','edt_operation_status','edt_merge_apply'}
 for attempt in range(60):
  try:
   c=module.Client(a.config,a.server)
   if required<={t['name'] for t in c.rpc('tools/list',{})['tools']}:break
  except Exception:pass
  time.sleep(1)
 else:raise RuntimeError('Bridge registry not ready')
 report={'cases':{},'output':str(out),'source_dt':str(dt)};source='BridgeCreationInfobase'+uuid.uuid4().hex[:10];project=out/'project';imported='BridgeInfobaseAudit'+uuid.uuid4().hex[:10]
 if a.resume_after_restore:
  report=json.loads((out/'report.json').read_text(encoding='utf-8'))
  if report.get('restore_new',{}).get('state')!='DONE' or report.get('import_new',{}).get('state')!='FAILED' or 'prepare_compare' in report:raise RuntimeError('Only a failed import immediately after successful restore can be resumed')
  source=report['source_project'];imported=report['import_new_preview']['project']
  marker=json.loads((project/'.edt-bridge-creation-audit.json').read_text(encoding='utf-8'))
  if not source.startswith('BridgeCreation') or marker['project']!=source or marker['configuration_uuid']!=ET.parse(project/'src/Configuration/Configuration.mdo').getroot().attrib['uuid']:raise RuntimeError('Fixture identity changed')
 def save(): (out/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=True),encoding='utf-8')
 def job(value,key):
  report[key+'_job']=value;save();last=None;deadline=time.monotonic()+300
  while time.monotonic()<deadline:
   status=c.call('edt_operation_status',{'job_id':value['job_id']});stage=status.get('progress',{}).get('stage',status['state'])
   if stage!=last:print(key+': '+stage,flush=True);last=stage
   if status['state'] in ['DONE','FAILED']:
    report[key]=status;save()
    if status['state']!='DONE':raise RuntimeError(key+' failed: '+status.get('error',''))
    return status['result']
   time.sleep(.5)
  raise TimeoutError(key)
 def apply(tool,request,key):
  if a.resume_after_restore and key=='restore_new':return report[key]['result']
  for attempt in range(120):
   try:preview=c.call(tool,request);break
   except RuntimeError as error:
    if 'EDT_PROJECT_NOT_READY' not in str(error) and 'corredponding DT' not in str(error):raise
    time.sleep(.5)
  else:raise RuntimeError('Project context did not become ready for preview')
  report[key+'_preview']=preview;save()
  return job(c.call(tool,{**request,'dry_run':False,'expected_preview_sha256':preview['preview_sha256']}),key)
 def check(key,value):
  report['cases'][key]=bool(value);save()
  if not value:raise AssertionError(key)
 try:
  if not a.resume_after_restore:job(c.call('edt_project_create',{'project':source,'project_path':str(project),'configuration_name':'InfobaseLifecycleFixture','version':a.version}),'create_project')
  report['source_project']=source;save()
  for attempt in range(120):
   if (project/'src/Configuration/Configuration.mdo').is_file():break
   time.sleep(.5)
  config=ET.parse(project/'src/Configuration/Configuration.mdo').getroot()
  (project/'.edt-bridge-creation-audit.json').write_text(json.dumps({'purpose':'edt-bridge-creation-coverage','project':source,'configuration_uuid':config.attrib['uuid']}),encoding='utf-8')
  for attempt in range(0 if a.resume_after_restore else 120):
   try:
    c.call('edt_infobase_provision',{'project':source,'database_path':str(out/'database'),'runtime_version':a.runtime_version});break
   except RuntimeError as e:
    if 'READY' not in str(e) and 'corredponding DT' not in str(e):raise
    time.sleep(.5)
  if not a.resume_after_restore:c.mutate('create_metadata',{'project':source,'kind':'CommonModule','name':'SourceOwned','properties':{'server':True,'externalConnection':True,'clientOrdinaryApplication':True}})
  request={'project':source,'database_path':str(out/'database'),'dt_path':str(dt),'runtime_version':a.runtime_version}
  result=apply('edt_infobase_provision',request,'restore_new')
  check('native_restore_and_full_reload',result.get('restore_verified') and result.get('application_bound') and result.get('update_mode')=='full' and result.get('equality')=='EQUAL')
  new=apply('edt_project_from_application',{'mode':'create','project':imported,'project_path':str(out/imported),'database_path':str(out/'database'),'runtime_version':a.runtime_version,'disposable_test_project':True,'reassign_from_project':source},'import_new')
  check('native_import_model_and_binding',new.get('model_ready') and new.get('application_bound'))
  check('imported_configuration_contains_project_change',(out/imported/'src/CommonModules/SourceOwned/SourceOwned.mdo').is_file())
  c.mutate('create_metadata',{'project':source,'kind':'CommonModule','name':'DestinationOnly','properties':{'server':True,'externalConnection':True,'clientOrdinaryApplication':True}})
  prepared=apply('edt_project_from_application',{'mode':'compare','project':source,'database_path':str(out/'database'),'runtime_version':a.runtime_version},'prepare_compare')
  sid=prepared['comparison']['session_id'];report['session_id']=sid;save()
  for attempt in range(120):
   status=c.call('edt_compare',{'action':'status','session_id':sid})
   if status.get('status')=='COMPARISON_PROCESS_FINISHED':break
   time.sleep(.5)
  tree=c.call('edt_compare',{'action':'tree','session_id':sid,'changes_only':False,'limit':500});report['comparison_tree']=tree;save()
  check('destination_still_unchanged_before_apply',(project/'src/CommonModules/DestinationOnly/DestinationOnly.mdo').is_file())
  rootnode=next(n for n in tree['nodes'] if n.get('parent_id') is None)
  detail=c.call('edt_compare',{'action':'details','session_id':sid,'node_id':rootnode['node_id']})
  c.call('edt_merge_plan',{'session_id':sid,'node_id':rootnode['node_id'],'rule':'take_other','recursive':True,'expected_node_sha256':detail['node_sha256']})
  plan=c.call('edt_merge_plan',{'session_id':sid});report['merge_apply']=c.call('edt_merge_apply',{'session_id':sid,'expected_plan_sha256':plan['plan_sha256']});save()
  for attempt in range(120):
   status=c.call('edt_compare',{'action':'status','session_id':sid})
   if status.get('applied'):break
   time.sleep(.5)
  check('native_merge_finished_successfully',status.get('applied') and not status.get('failure'))
  check('native_merge_removed_destination_only_object',not (project/'src/CommonModules/DestinationOnly/DestinationOnly.mdo').exists())
  report['diagnostics']=c.call('get_diagnostics',{'scope':'project','project_name':source,'severity':'error','max_items':200,'wait_ms':2000});save()
  check('merged_project_has_no_error_diagnostics','**Итого:** 0 ошибок' in report['diagnostics'].get('text',''))
  report['snapshot_cleanup']=c.call('edt_project_from_application',{'action':'cleanup','import_id':prepared['import_id']});save()
  # Imported project is still bound to the same base; its own synchronization is not started.
  report['import_cleanup']=c.call('edt_project_from_application',{'action':'cleanup','import_id':new['import_id']});save()
  restored=apply('edt_infobase_provision',{**request,'restore_existing':True},'restore_existing')
  check('existing_base_backup_before_restore',Path(restored['backup_dt']).is_file() and Path(restored['backup_dt']).stat().st_size>0)
  check('existing_restore_full_reload',restored.get('equality')=='EQUAL' and restored.get('update_mode')=='full')
  report['source_cleanup']=job(c.call('edt_creation_audit_cleanup',{'project':source,'expected_project_path':str(project),'dry_run':False}),'cleanup_source');save()
  check('source_project_removed',report['source_cleanup'].get('removed'))
  report['status']='passed';save();print(json.dumps({'status':'passed','cases':report['cases'],'report':str(out/'report.json')}))
 except Exception as e:
  report['status']='failed';report['error']=str(e);save();raise
if __name__=='__main__':
 sys.stdout.reconfigure(encoding='utf-8');main()
