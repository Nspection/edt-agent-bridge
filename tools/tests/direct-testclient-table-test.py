# SPDX-License-Identifier: AGPL-3.0-only
"""Deterministic table semantics and failure boundaries; no 1C or desktop input."""
import importlib.util
from pathlib import Path
import sys,types,unittest
sys.dont_write_bytecode=True
base=Path(__file__).parents[2]/"bundles/com.codepilot1c.core/resources/test-client"
sys.path.insert(0,str(base))
from tables import TableEngine
spec=importlib.util.spec_from_file_location("table_worker",base/"worker.py");worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)
NAMES="CURRENT_VISIBLE CURRENT_ENABLE CURRENT_READONLY CURRENT_MODE_IS_EDIT GET_CHILD_OBJECTS GET_CURRENT_ROW GET_SELECTED_ROWS GET_CELL_TEXT GET_CURRENT_ITEM GOTO_FIRST_ROW GOTO_ROW DESELECT_ALL_ROWS SELECT_ALL_ROWS ACTIVATE ADD_ROW CHANGE_ROW INPUT_TEXT CLEAR END_EDIT_ROW".split()
class Fake(worker.Worker):
 def __init__(self,rows=()):
  super().__init__()
  self.rows=[dict(r,N=str(i+1)) for i,r in enumerate(rows)];self.index=0;self.selection=[];self.editing=False;self.readonly=False;self.single=False;self.dispatched=False;self.writes=0;self.broken=False;self.lose=False;self.alter=False;self.redirect=False;self.active=None;self.client=types.SimpleNamespace(close=lambda:None)
  self.guids=types.SimpleNamespace(**{n:n for n in NAMES})
  self.wire=types.SimpleNamespace(decode_rows=lambda r:r,decode_goto_row=lambda r:r,decode_bool=lambda r:r,decode_cell_text=lambda r:(True,r),mk_cell=lambda n:n,mk_input_text=lambda t:t,mk_gotorow=lambda fields:fields,GOTOROW_PAD=7)
  self.table={"name":"Lines","key":"table","handle":"table-h","class":"Table"}
  self.cols=[{"name":n,"title":n,"key":n,"handle":n+"-h","class":"EditField","type":"InputField"} for n in ["Key","Text","Private"]]
 def form(self):return {"key":"window"},{"key":"form","handle":"form-h"},{"title":"Card"}
 def elements(self,form):return [self.table]
 def one(self,items,name,expected=None):
  found=[i for i in items if i['name']==name]
  if len(found)!=1 or expected and found[0]['class'] not in expected:raise worker.Rejected('ambiguous element')
  return found[0]
 def collection(self,guid,key,middle=None):return self.cols if guid=='GET_CHILD_OBJECTS' else [c for c in self.cols if c['key']==self.active]
 def send(self,guid,key=None,kind='read',middle=None,handle=None,pad=None):
  if guid=='CURRENT_VISIBLE' or guid=='CURRENT_ENABLE':return True
  if guid=='CURRENT_READONLY':return self.readonly
  if guid=='CURRENT_MODE_IS_EDIT':return self.editing
  if guid=='GET_CURRENT_ROW':return [dict(self.rows[self.index])] if self.rows else []
  if guid=='GET_SELECTED_ROWS':return [dict(self.rows[i]) for i in self.selection]
  if guid=='GET_CELL_TEXT':return self.rows[self.index].get(middle)
  if guid=='GOTO_FIRST_ROW':self.index=0;return True
  if guid=='GOTO_ROW':
   for i,r in enumerate(self.rows):
    if all(r.get(n)==v for n,v in middle):self.index=i;return True
   return False
  raise AssertionError(guid)
 def mutate(self,guid,key,middle=b'',handle=None):
  self.dispatched=True
  if guid=='DESELECT_ALL_ROWS':self.selection=[]
  elif guid=='SELECT_ALL_ROWS':
   if not self.single:self.selection=list(range(len(self.rows)))
  elif guid=='ACTIVATE':
   self.active=key
   if self.redirect and key=='Text':self.index=0
  elif guid=='ADD_ROW':self.rows.append({'N':str(len(self.rows)+1),'Key':'','Text':'','Private':''});self.index=len(self.rows)-1;self.writes+=1;self.editing=True
  elif guid=='CHANGE_ROW':self.editing=True
  elif guid in ['INPUT_TEXT','CLEAR']:
   self.rows[self.index][key]=middle if guid=='INPUT_TEXT' else '';self.writes+=1
   if self.lose:raise OSError('reply lost after data effect')
  elif guid=='END_EDIT_ROW':
   self.editing=False
   if self.alter and self.active=='Text':self.rows[self.index]['Key']='handler changed'
  else:raise AssertionError(guid)
 def dispatch(self,request):
  if self.broken:raise worker.Rejected('channel broken')
  return TableEngine(self,worker.Rejected).run(request)
def request(action,**kw):return dict(action=action,table='Lines',**kw)
class Tables(unittest.TestCase):
 def engine(self,w):return TableEngine(w,worker.Rejected)
 def test_read_projection_filter_paging_and_duplicate_counts(self):
  w=Fake([{'Key':'A','Text':'x','Private':'secret'},{'Key':'A','Text':'x','Private':'secret'},{'Key':'B','Text':'y','Private':'secret'}])
  r=self.engine(w).run(request('table_read',columns=['Text'],row_match={'Key':'A'},offset=1,max_rows=1,expect_count=2,expect_rows=[{'Text':'x'}]))
  self.assertEqual(r['rows'],[{'Text':'x'}]);self.assertEqual(r['row_count'],2);self.assertTrue(r['postcondition_verified']);self.assertNotIn('secret',str(r));self.assertEqual(w.writes,0)
 def test_duplicate_and_absent_match_never_edit(self):
  for match in ['A','missing']:
   w=Fake([{'Key':'A','Text':'old'},{'Key':'A','Text':'old'}]);r=self.engine(w).run(request('table_set',row_match={'Key':match},cells={'Text':'new'}))
   self.assertFalse(r['success']);self.assertFalse(r['data_changed']);self.assertEqual(w.writes,0)
 def test_unique_selection_and_set_verify_target(self):
  w=Fake([{'Key':'A','Text':'old'},{'Key':'B','Text':'old'}]);r=self.engine(w).run(request('table_set',row_match={'Key':'B'},cells={'Text':'new'}))
  self.assertTrue(r['postcondition_verified']);self.assertEqual(w.rows[0]['Text'],'old');self.assertEqual(w.rows[1]['Text'],'new')
 def test_focus_redirect_cannot_write_another_row(self):
  w=Fake([{'Key':'A','Text':'old'},{'Key':'B','Text':'old'}]);w.redirect=True
  with self.assertRaises(worker.Rejected):self.engine(w).run(request('table_set',row_match={'Key':'B'},cells={'Text':'new'}))
  self.assertEqual(w.writes,0);self.assertEqual(w.rows[0]['Text'],'old')
 def test_add_fills_and_finishes_row(self):
  w=Fake([{'Key':'Seed','Text':'seed'}]);r=self.engine(w).run(request('table_add',cells={'Key':'A','Text':'value'}))
  self.assertTrue(r['success']);self.assertTrue(r['selection_changed']);self.assertEqual(w.rows[-1]['Text'],'value');self.assertFalse(w.editing)
 def test_readonly_and_unknown_column_refuse_before_effect(self):
  for readonly,col in [(True,'Key'),(False,'unknown')]:
   w=Fake();w.readonly=readonly
   with self.assertRaises(worker.Rejected):self.engine(w).run(request('table_add',cells={col:'A'}))
   self.assertFalse(w.dispatched);self.assertEqual(w.writes,0)
 def test_empty_write_refused_before_any_effect(self):
  w=Fake()
  with self.assertRaises(worker.Rejected):self.engine(w).run(request('table_add',cells={'Key':''}))
  self.assertFalse(w.dispatched);self.assertEqual(w.writes,0)
 def test_single_selection_cannot_claim_complete_one_row(self):
  w=Fake([{'Key':'A'}]);w.single=True
  r=self.engine(w).run(request('table_read',columns=['Key']));self.assertFalse(r['success']);self.assertEqual(r['reason'],'table_rows_unproven')
 def test_final_readback_detects_handler_change_to_earlier_cell(self):
  w=Fake([{'Key':'Seed','Text':'seed'}]);w.alter=True
  with self.assertRaises(worker.Rejected):self.engine(w).run(request('table_add',cells={'Key':'A','Text':'value'}))
 def test_lost_write_breaks_channel_without_replay(self):
  w=Fake([{'Key':'A','Text':'old'}]);w.lose=True;a=request('table_set',row_match={'Key':'A'},cells={'Text':'new'})
  r=worker.Worker.handle(w,a);self.assertTrue(r['outcome_unknown']);self.assertTrue(w.broken);effects=w.writes
  r=worker.Worker.handle(w,a);self.assertFalse(r['success']);self.assertEqual(w.writes,effects)
 def test_unavailable_cell_never_passes_empty_text_assertion(self):
  w=Fake([{'Key':'A','Text':None}]);r=self.engine(w).run(request('table_read',columns=['Text'],expect_rows=[{'Text':''}]))
  self.assertFalse(r['success']);self.assertIsNone(r['rows'][0]['Text'])
class ReviewRegressions(unittest.TestCase):
 def test_no_current_does_not_prove_empty(self):
  class NoCurrent(Fake):
   def send(self,guid,*a,**kw):
    return [] if guid=='GET_CURRENT_ROW' else super().send(guid,*a,**kw)
  for w in [Fake(),NoCurrent([{'Key':'A'}])]:
   w.single=True;r=TableEngine(w,worker.Rejected).run(request('table_read',columns=['Key'],expect_count=0,expect_rows=[]))
   self.assertFalse(r['success']);self.assertFalse(r['postcondition_verified']);self.assertIsNone(r['row_count'])
 def test_cancelled_add_never_overwrites_current_row(self):
  class Cancel(Fake):
   def mutate(self,guid,*a,**kw):
    if guid=='ADD_ROW':self.dispatched=True;return
    return super().mutate(guid,*a,**kw)
  w=Cancel([{'Key':'Existing','Text':'old'}])
  with self.assertRaises(worker.Rejected):TableEngine(w,worker.Rejected).run(request('table_add',cells={'Key':'new','Text':'new'}))
  self.assertEqual(w.writes,0);self.assertEqual(w.rows[0]['Text'],'old')
 def test_redirect_after_added_row_proof_cannot_rebind_anchor(self):
  class Redirect(Fake):
   moved=False
   def send(self,guid,*a,**kw):
    result=super().send(guid,*a,**kw)
    if guid=='GET_CURRENT_ROW' and len(self.rows)==2 and not self.moved:self.moved=True;self.index=0
    return result
  w=Redirect([{'Key':'Existing','Text':'old'}])
  with self.assertRaises(worker.Rejected):TableEngine(w,worker.Rejected).run(request('table_add',cells={'Key':'new','Text':'value'}))
  self.assertEqual(w.rows[0]['Text'],'old');self.assertEqual(w.writes,1)
 def test_end_edit_redirect_never_rebinds_anchor(self):
  class Redirect(Fake):
   def mutate(self,guid,*a,**kw):
    super().mutate(guid,*a,**kw)
    if guid=='END_EDIT_ROW':self.index=0
  w=Redirect([{'Key':'A','Text':'new','Private':'old-A'},{'Key':'B','Text':'old-B','Private':'old-B'}])
  with self.assertRaises(worker.Rejected):TableEngine(w,worker.Rejected).run(request('table_set',row_match={'Key':'B'},cells={'Text':'new','Private':'secret2'}))
  self.assertEqual(w.rows[0]['Private'],'old-A');self.assertEqual(w.rows[1]['Private'],'old-B');self.assertEqual(w.rows[1]['Text'],'new')
 def test_final_window_and_fields_use_one_capture(self):
  class W(Fake):
   def __init__(self):super().__init__();self.snapshots={'s':{'signature':('window','form-h','Card'),'elements':[self.table]}};self.captures=[]
   def postcondition(self,r):return worker.Worker.postcondition(self,r)
   def wait_capture(self,r,*a,**kw):return worker.Worker.wait_capture(self,r,*a,**kw)
   def identity(self):pass
   def capture(self,r):self.captures.append(r);return {'success':True,'window':{'title':'Other'},'values':{'Status':'Done'}}
  w=W();w.client=type('Client',(),{})();original=worker.TableEngine
  class Engine:
   def __init__(self,*a):pass
   def run(self,*a):return {'success':True,'postcondition_verified':True}
  try:
   worker.TableEngine=Engine;r=worker.Worker.act(w,{'action':'table_read','snapshot_id':'s','expected_window':'Card','wait_timeout_ms':0,'expect':{'window':'Card','fields':{'Status':'Done'}}})
   self.assertFalse(r['success']);self.assertFalse(r['postcondition_verified']);self.assertEqual(len(w.captures),1)
  finally:worker.TableEngine=original
if __name__=='__main__':unittest.main()
