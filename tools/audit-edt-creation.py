"""Exercise advertised creation contracts in a NEW isolated EDT project.

Requires a running Bridge and explicit --apply. Never connects/updates an infobase.
Writes detailed local evidence; --verify-only re-reads saved objects after an EDT restart.
Authentication is read from local Codex settings and never emitted in reports.
"""
from pathlib import Path
import json,tomllib,urllib.request
class Client:
 def __init__(self, config, server):
  self.cfg=tomllib.loads(Path(config).read_text(encoding='utf-8'))['mcp_servers'][server];self.headers=dict(self.cfg.get('http_headers',{}));self.headers.update({'Accept':'application/json, text/event-stream','Content-Type':'application/json'});self.i=0
  self.rpc('initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'bridge-creation-audit','version':'1'}})
 def rpc(self,method,params):
  self.i+=1;req=urllib.request.Request(self.cfg['url'],data=json.dumps({'jsonrpc':'2.0','id':self.i,'method':method,'params':params}).encode(),headers=self.headers)
  with urllib.request.urlopen(req,timeout=180) as r:
   if r.headers.get('Mcp-Session-Id'):self.headers['Mcp-Session-Id']=r.headers['Mcp-Session-Id']
   raw=r.read().decode();data=json.loads(raw) if raw.lstrip().startswith('{') else next(json.loads(x[5:]) for x in raw.splitlines() if x.startswith('data:'))
   if 'error' in data:raise RuntimeError(str(data['error']))
   return data['result']
 def call(self,name,args):
  r=self.rpc('tools/call',{'name':name,'arguments':args})
  if r.get('isError'):raise RuntimeError(str(r.get('content'))[:4000])
  texts=[x['text'] for x in r.get('content',[]) if x.get('type')=='text']
  try:return json.loads(texts[0])
  except (ValueError,IndexError):return {'text':'\n'.join(texts)}
 def mutate(self,name,args):
  v=self.call('edt_validate_request',{'operation':name,'project':args['project'],'payload':args});token=v.get('validationToken') or v.get('validation_token')
  if not token:raise RuntimeError(str(v))
  return self.call(name,{**args,'validation_token':token})

import argparse,time,xml.etree.ElementTree as ET

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--config',default=str(Path.home()/'.codex/config.toml'))
 p.add_argument('--server',default='edt_bridge');p.add_argument('--project',required=True)
 p.add_argument('--output',required=True);p.add_argument('--version',default='8.5.1')
 p.add_argument('--apply',action='store_true');p.add_argument('--verify-only',action='store_true')
 a=p.parse_args();out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
 if not a.project.startswith('BridgeCreation') or '/.runs/creation-audit/' not in (out/'project').as_posix():p.error('Use a BridgeCreation project under .runs/creation-audit for safe disposable fixtures')
 c=Client(a.config,a.server);schemas=c.rpc('tools/list',{})['tools']
 kinds=next(t for t in schemas if t['name']=='create_metadata')['inputSchema']['properties']['kind']['enum']
 if not a.apply and not a.verify_only:
  print(json.dumps({'project':a.project,'kinds':kinds,'apply_required':True}));return
 state=out/'audit.json'
 if a.verify_only:
  ready_deadline=time.monotonic()+120
  while True:
   try:c.call('edt_creation_defaults',{'project':a.project,'class_name':'Catalog','fields':['codeLength']});break
   except Exception:
    if time.monotonic()>ready_deadline:raise
    time.sleep(1)
  report=json.loads(state.read_text(encoding='utf-8'));assert report['project']==a.project
  rows=report['cases'];before=report['snapshots'];after={};failures=[]
  for fqn,old in before.items():
   try:
    current=c.call('edt_creation_defaults',{'project':a.project,'target_fqn':fqn})
    # Independent reads create fresh random native UUIDs. Compare actual persisted scalar values.
    actual=current['actual'];after[fqn]=current
    for name,prop in old['actual'].items():
     value=prop.get('effective')
     if value is None or isinstance(value,(str,bool,int,float)):
      if actual.get(name,{}).get('effective')!=value:failures.append({'fqn':fqn,'property':name,'before':value,'after':actual.get(name,{}).get('effective')})
   except Exception as e:failures.append({'fqn':fqn,'error':str(e)})
  form=c.call('inspect_form_layout',{'project':a.project,'form_fqn':'Catalog.AuditCatalog.Form.AuditAUXILIARY','include_properties':True,'include_attributes':True,'max_items':10000})
  report['restart_verification']={'objects':len(after),'scalar_mismatches':failures,'form_layout_equal':form==report.get('form_snapshot')}
  state.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
  print(json.dumps(report['restart_verification'],ensure_ascii=False))
  if failures or not report['restart_verification']['form_layout_equal']:raise SystemExit(1)
  return
 if state.exists() or (out/'project').exists():p.error('Use a new output directory; this audit never recreates or overwrites an existing project')
 report={'project':a.project,'platform':a.version,'bridge':c.call('edt_bridge_status',{}),'cases':[],'snapshots':{}}
 def save():state.write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
 def check(label,tool,args):
  row={'case':label,'tool':tool,'arguments':args}
  try:row['result']=c.mutate(tool,{'project':a.project,**args});row['status']='created'
  except Exception as e:row['status']='failed';row['error']=str(e)
  report['cases'].append(row);save();print(label,row['status'],row.get('error','')[:200],flush=True);return row
 job=c.call('edt_project_create',{'project':a.project,'configuration_name':a.project,'version':a.version,'project_path':str(out/'project')})
 deadline=time.monotonic()+120
 while True:
  status=c.call('edt_operation_status',{'job_id':job['job_id']})
  if status['state']=='DONE':break
  if status['state'] in ['FAILED','CANCELLED'] or time.monotonic()>deadline:raise RuntimeError(str(status))
  time.sleep(1)
 deadline=time.monotonic()+120
 while True:
  try:c.call('edt_creation_defaults',{'project':a.project,'class_name':'Catalog','fields':['codeLength']});break
  except Exception:
   if time.monotonic()>deadline:raise
   time.sleep(1)
 configuration=ET.parse(out/'project/src/Configuration/Configuration.mdo').getroot()
 (out/'project/.edt-bridge-creation-audit.json').write_text(json.dumps({'purpose':'edt-bridge-creation-coverage','project':a.project,'configuration_uuid':configuration.get('uuid')}),encoding='utf-8')
 for kind in kinds:
  args={'kind':kind,'name':'Audit'+kind}
  # Language requires a real code; no implicit invented language selection.
  if kind=='Language':args['properties']={'languageCode':'en'}
  check('metadata/'+kind,'create_metadata',args)
 owner='Catalog.AuditCatalog'
 specs=[('Attribute',owner,{'properties':{'type':'String','length':30}}),('Tabular_Section','Document.AuditDocument',{}),('Command',owner,{}),('Form',owner,{'form_usage':'OBJECT','wait_ms':5000}),('Template','DataProcessor.AuditDataProcessor',{}),('Dimension','InformationRegister.AuditInformationRegister',{'properties':{'type':'String','length':30}}),('Resource','AccumulationRegister.AuditAccumulationRegister',{'properties':{'type':'Number','precision':15,'scale':3}}),('Requisite','InformationRegister.AuditInformationRegister',{'properties':{'type':'String','length':30}}),('EnumValue','Enum.AuditEnum',{}),('URLTemplate','HTTPService.AuditHTTPService',{'template':'/audit'}),('HTTPMethod','HTTPService.AuditHTTPService.URLTemplate.AuditURLTemplate',{'http_method':'GET','handler':'AuditGet'})]
 for kind,parent,extra in specs:check('child/'+kind,'add_metadata_child',{'parent_fqn':parent,'child_kind':kind,'name':'Audit'+kind.replace('_',''),**extra})
 for usage in ['OBJECT','LIST','CHOICE','AUXILIARY']:check('form/'+usage,'create_form',{'owner_fqn':owner,'name':'Audit'+usage,'usage':usage,'set_as_default':False,'wait_ms':5000})
 for t in ['spreadsheet','html','text','binary','dcs','active_document']:check('template/'+t,'add_metadata_child',{'parent_fqn':'DataProcessor.AuditDataProcessor','child_kind':'Template','name':'Template'+t.replace('_',''),'template_type':t})
 fqn=owner+'.Form.AuditAUXILIARY'
 check('form/attributes','apply_form_recipe',{'form_fqn':fqn,'attributes':[{'name':'AuditString','type':'String(30)'},{'name':'AuditBoolean','type':'Boolean'},{'name':'AuditNumber','type':'Number(15,3)'},{'name':'AuditDate','type':'Date'},{'name':'AuditRows','type':'ValueTable','columns':[{'name':'Label','type':'String(30)'},{'name':'Quantity','type':'Number(15,3)'}]}]})
 def layout(label,ops):return check(label,'mutate_form_model',{'form_fqn':fqn,'operations':ops})
 for group in ['USUAL_GROUP','PAGES','COMMAND_BAR']:layout('group/'+group,[{'op':'add_group','name':'Group'+group.replace('_',''),'group_type':group}])
 layout('group/PAGE',[{'op':'add_group','name':'AuditPage','group_type':'PAGE','parent_item_name':'GroupPAGES'}])
 for field in ['INPUT_FIELD','LABEL_FIELD','CHECK_BOX_FIELD','RADIO_BUTTON_FIELD','PICTURE_FIELD','HTML_DOCUMENT_FIELD','TEXT_DOCUMENT_FIELD','SPREADSHEET_DOCUMENT_FIELD','CHART_FIELD','GANTT_CHART_FIELD','PROGRESS_BAR_FIELD','TRACK_BAR_FIELD','CALENDAR_FIELD','PERIOD_FIELD','PDF_DOCUMENT_FIELD','GEOGRAPHICAL_SCHEMA_FIELD','GRAPHICAL_SCHEMA_FIELD','DENDROGRAM_FIELD','FORMATTED_DOCUMENT_FIELD','PLANNER_FIELD']:
  layout('field/'+field,[{'op':'add_field','name':'Field'+field.replace('_',''),'type':field}])
 layout('table/ValueTable',[{'op':'add_table','name':'AuditTable','data_path':'AuditRows'}])
 for group,parent in [('BUTTON_GROUP','GroupCOMMANDBAR'),('POPUP','GroupCOMMANDBAR'),('COLUMN_GROUP','AuditTable')]:layout('group/'+group,[{'op':'add_group','name':'Group'+group.replace('_',''),'group_type':group,'parent_item_name':parent}])
 layout('command/FormCommand',[{'op':'add_command','name':'AuditCommand','handler':'AuditCommandHandler'}])
 for button in ['USUAL_BUTTON','HYPERLINK','COMMAND_BAR_BUTTON','COMMAND_BAR_HYPERLINK']:layout('button/'+button,[{'op':'add_button','name':'Button'+button.replace('_',''),'button_type':button,'command':'AuditCommand'}])
 for kind in kinds:
  fqn=kind+'.Audit'+kind
  try:report['snapshots'][fqn]=c.call('edt_creation_defaults',{'project':a.project,'target_fqn':fqn})
  except Exception as e:report.setdefault('inspection_errors',{})[fqn]=str(e)
 for kind,parent,extra in specs:
  marker={'Tabular_Section':'TabularSection','Requisite':'Attribute','HTTPMethod':'Method'}.get(kind,kind)
  fqn=parent+'.'+marker+'.Audit'+kind.replace('_','')
  try:report['snapshots'][fqn]=c.call('edt_creation_defaults',{'project':a.project,'target_fqn':fqn})
  except Exception as e:report.setdefault('inspection_errors',{})[fqn]=str(e)
 report['form_snapshot']=c.call('inspect_form_layout',{'project':a.project,'form_fqn':owner+'.Form.AuditAUXILIARY','include_properties':True,'include_attributes':True,'max_items':10000})
 # Persisted XML must be parseable and carry unique object UUIDs. Identity references are not definitions.
 uuids={};duplicates=[];files=list((out/'project/src').rglob('*.mdo'))+list((out/'project/src').rglob('*.form'))
 for file in files:
  for node in ET.parse(file).getroot().iter():
   uuid=node.get('uuid')
   if uuid:
    if uuid in uuids:duplicates.append({'uuid':uuid,'files':[uuids[uuid],str(file.relative_to(out/'project'))]})
    else:uuids[uuid]=str(file.relative_to(out/'project'))
 # Default scalar comparison intentionally excludes generated identity and explicit test inputs.
 comparisons=0;default_differences=[]
 for target,snapshot in report['snapshots'].items():
  explicit={'uuid','name','thisNode'}
  if target.startswith('Language.'):explicit.add('languageCode')
  if '.URLTemplate.' in target:explicit.update({'template','handler'})
  for key,prop in snapshot['native_defaults'].items():
   value=prop.get('effective');actual=snapshot['actual'].get(key,{}).get('effective')
   if key not in explicit and (value is None or isinstance(value,(str,int,bool,float))) and (actual is None or isinstance(actual,(str,int,bool,float))):
    comparisons+=1
    if value!=actual:default_differences.append({'fqn':target,'property':key,'native':value,'actual':actual})
 report['default_comparison']={'scalar_properties':comparisons,'unexpected_differences':default_differences,'note':'References/containment, generated identity and explicit test inputs are accounted separately; no wizard equivalence is asserted.'}
 form_file=out/'project/src/Catalogs/AuditCatalog/Forms/AuditAUXILIARY/Form.form'
 form=ET.parse(form_file).getroot();field_types={}
 for item in form.iter():
  name=item.findtext('name') or ''
  if name.startswith('Field') and name.endswith('FIELD'):
   info=item.find('extInfo');field_types[name]={'type':item.findtext('type'),'ext_info':info.get('{http://www.w3.org/2001/XMLSchema-instance}type') if info is not None else None}
 report['xml']={'parsed_files':len(files),'duplicate_uuids':duplicates,'field_types':field_types,'automatic_command_bars':len(form.findall('.//autoCommandBar')),'common_form_model_present':(out/'project/src/CommonForms/AuditCommonForm/Form.form').is_file()}
 report['diagnostics']=c.call('get_diagnostics',{'scope':'project','project_name':a.project,'severity':'error','max_items':0,'wait_ms':2000})
 save();print(json.dumps({'cases':len(report['cases']),'created':sum(r['status']=='created' for r in report['cases']),'snapshots':len(report['snapshots']),'xml':report['xml']}))
 if any(row['status']!='created' for row in report['cases']) or report.get('inspection_errors') or default_differences or duplicates or not report['xml']['common_form_model_present']:raise SystemExit(1)
if __name__=='__main__':main()
