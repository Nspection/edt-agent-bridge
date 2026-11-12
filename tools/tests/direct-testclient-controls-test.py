# SPDX-License-Identifier: AGPL-3.0-only
"""Typed state changes, identity drift and transport boundaries without a 1C client."""
from pathlib import Path
import sys,types,unittest
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).parents[2]/'bundles/com.codepilot1c.core/resources/test-client'))
from controls import ControlEngine, checked, potential_actions
class Reject(ValueError):pass
class Fake:
 def __init__(self,kind='CheckBoxField',state='No'):
  self.item=dict(key='field',handle='field-h',name='Control',type=kind,**{'class':'EditField'});self.text=state;self.effects=[];self.enabled=True;self.readonly=False;self.lost=False;self.drift=False;self.switch=False;self.deadline=float('inf')
  names='CURRENT_VISIBLE CURRENT_ENABLE CURRENT_READONLY SET_CHECK GET_CURRENT_PAGE ACTIVATE FORM_GET_CURRENT_ITEM GET_CHOICE_LIST OPEN_DROP_LIST DROP_LIST_IS_OPEN SELECT_OPTION CHOOSE_FROM_DROP_LIST INCREASE_VALUE DECREASE_VALUE GOTO_DATE GOTO_VALUE EXPAND_GROUP COLLAPSE_GROUP CLICK_DECORATION CLICK_FIELD'.split();self.guids=types.SimpleNamespace(**{n:n for n in names});self.wire=types.SimpleNamespace(decode_bool=lambda x:x,decode_collection=lambda x,k:x,decode_choice_items=lambda x:(x,'ok'),mk_choice=lambda x:x,mk_date=lambda *x:x,mk_goto_value=lambda x:x)
 def form(self):return {'key':'other' if self.switch else 'window'},{'key':'form','handle':'form-h'},{}
 def one(self,items,name):
  found=[x for x in items if x['name']==name]
  if len(found)!=1:raise Reject('ambiguous')
  return found[0]
 def value(self,item):return self.text
 def send(self,g,key=None,**kw):
  if g=='CURRENT_VISIBLE':return True
  if g=='CURRENT_ENABLE':return self.enabled
  if g=='CURRENT_READONLY':return self.readonly
  if g=='DROP_LIST_IS_OPEN':return True
  if g=='GET_CHOICE_LIST':return [{'text':'One','presentation':'1'},{'text':'Two','presentation':'2'}]
  if g=='GET_CURRENT_PAGE':return [dict(key='p2' if self.switch else 'p1',handle='p2-h' if self.switch else 'p1-h')]
  raise AssertionError(g)
 def mutate(self,g,key,middle=b'',handle=None):
  self.effects.append(g)
  if self.lost:raise TimeoutError('lost reply')
  if g=='SET_CHECK':self.text='No' if checked(self.text) else 'Yes'
  elif g=='CHOOSE_FROM_DROP_LIST' or g=='SELECT_OPTION':self.text=middle
  elif g=='INCREASE_VALUE':self.text=str(int(self.text)+1)
  elif g=='DECREASE_VALUE':self.text=str(int(self.text)-1)
  elif g=='GOTO_DATE':self.text='%04d-%02d-%02d'%middle
  elif g=='GOTO_VALUE':self.text=str(middle)
  if self.drift:self.switch=True
 def run(self,action,**kw):return ControlEngine(self,Reject).run(dict(action=action,element='Control',wait_timeout_ms=0,**kw),self.form()[1],[self.item])
class Controls(unittest.TestCase):
 def test_checkbox_delta_and_readback(self):
  w=Fake();self.assertTrue(w.run('check')['state_verified']);self.assertEqual(w.effects,['SET_CHECK']);self.assertTrue(w.run('check')['state_verified']);self.assertEqual(len(w.effects),1);self.assertTrue(w.run('uncheck')['state_verified']);self.assertEqual(w.text,'No')
 def test_unknown_checkbox_rejected_before_mutation(self):
  w=Fake(state='Unknown')
  with self.assertRaises(Reject):w.run('check')
  self.assertEqual(w.effects,[])
 def test_disabled_and_readonly_never_mutate(self):
  for flag in ['enabled','readonly']:
   w=Fake();setattr(w,flag,flag=='readonly')
   with self.assertRaises(Reject):w.run('check')
   self.assertEqual(w.effects,[])
 def test_lost_toggle_is_never_repeated(self):
  w=Fake();w.lost=True
  with self.assertRaises(TimeoutError):w.run('check')
  self.assertEqual(w.effects,['SET_CHECK'])
 def test_form_identity_rechecked_after_mutation(self):
  w=Fake();w.drift=True
  with self.assertRaises(Reject):w.run('check')
  self.assertEqual(w.effects,['SET_CHECK'])
 def test_form_change_during_value_read_prevents_toggle(self):
  w=Fake()
  def changed(item):w.switch=True;return 'No'
  w.value=changed
  with self.assertRaises(Reject):w.run('check')
  self.assertEqual(w.effects,[])
 def test_focus_requires_exact_handle(self):
  w=Fake('InputField','x');base=w.send
  w.send=lambda g,*args,**kwargs: [{'key':'field','handle':'replaced'}] if g=='FORM_GET_CURRENT_ITEM' else base(g,*args,**kwargs)
  r=w.run('activate');self.assertFalse(r['state_verified']);self.assertEqual(w.effects,['ACTIVATE'])
 def test_wrong_control_type_rejected(self):
  w=Fake('ProgressBarField')
  with self.assertRaises(Reject):w.run('check')
  self.assertEqual(w.effects,[])
 def test_radio_exact_choice_readback(self):
  w=Fake('RadioButtonField','One');self.assertTrue(w.run('choose_value',value='Two')['state_verified']);self.assertEqual(w.effects,['SELECT_OPTION'])
 def test_initially_unselected_radio_can_be_selected_and_verified(self):
  w=Fake('RadioButtonField',None);calls=[0]
  def value(item):
   calls[0]+=1
   if calls[0]==1:raise Reject('unknown empty radio scalar')
   return w.text
  w.value=value;r=w.run('choose_value',value='Two');self.assertTrue(r['state_verified']);self.assertIsNone(r['changed']);self.assertEqual(w.effects,['SELECT_OPTION'])
 def test_radio_choices_read_without_selecting(self):
  w=Fake('RadioButtonField','One');r=w.run('open_choices');self.assertTrue(r['state_verified']);self.assertEqual([x['text'] for x in r['choices']],['One','Two']);self.assertEqual(w.effects,[])
 def test_input_choices_open_once_without_value_write(self):
  w=Fake('InputField','One');base=w.send
  w.send=lambda g,*a,**k: bool(w.effects) if g=='DROP_LIST_IS_OPEN' else base(g,*a,**k)
  r=w.run('open_choices');self.assertTrue(r['state_verified']);self.assertEqual(w.effects,['OPEN_DROP_LIST']);self.assertEqual(w.text,'One')
 def test_calendar_command_does_not_probe_an_unsupported_scalar_getter(self):
  w=Fake('CalendarField')
  w.value=lambda item: (_ for _ in ()).throw(AssertionError('No calendar scalar read is supported'))
  r=w.run('set_date',date='2026-02-28');self.assertFalse(r['state_verified']);self.assertEqual(w.effects,['GOTO_DATE'])
 def test_input_choice_not_text_injection(self):
  w=Fake('InputField','One');self.assertTrue(w.run('choose_value',value='Two')['state_verified']);self.assertEqual(w.effects,['OPEN_DROP_LIST','CHOOSE_FROM_DROP_LIST'])
 def test_missing_radio_choice_no_mutation(self):
  w=Fake('RadioButtonField','One')
  with self.assertRaises(Reject):w.run('choose_value',value='missing')
  self.assertEqual(w.effects,[])
 def test_date_validation_and_exact_readback(self):
  w=Fake('CalendarField','2026-01-01')
  with self.assertRaises(Reject):w.run('set_date',date='2026-02-30')
  self.assertEqual(w.effects,[]);r=w.run('set_date',date='2026-02-28');self.assertFalse(r['state_verified']);self.assertIn('ack_only',r['verification']);self.assertEqual(w.effects,['GOTO_DATE'])
 def test_spinner_direction(self):
  w=Fake('InputField','12');self.assertTrue(w.run('increase')['state_verified']);self.assertEqual(w.text,'13');self.assertTrue(w.run('decrease')['state_verified'])
 def test_slider_percent_not_claimed_as_value(self):
  w=Fake('TrackBarField','0');self.assertFalse(w.run('set_position',position=40)['state_verified']);self.assertTrue(w.run('set_position',position=40,value='40')['state_verified'])
 def test_invalid_slider_percentage(self):
  for value in [True,-1,101,20.5]:
   w=Fake('TrackBarField','0')
   with self.assertRaises(Reject):w.run('set_position',position=value)
   self.assertEqual(w.effects,[])
 def test_selected_page_uses_current_page_not_visible(self):
  w=Fake();parent=dict(key='pages',handle='pages-h',name='Pages',type='Pages',**{'class':'Group'});page=dict(key='p2',handle='p2-h',name='Page2',parent='pages',type='Page',**{'class':'Group'});self.assertFalse(ControlEngine(w,Reject).page_selected(page,{'pages':parent}))
 def test_russian_checkbox_codec(self):
  self.assertTrue(checked("\u0414\u0430"));self.assertFalse(checked("\u041d\u0435\u0442"))
 def test_display_only_widget_has_no_actions(self):self.assertEqual(potential_actions({'class':'EditField','type':'ProgressBarField'}),[])
if __name__=='__main__':unittest.main()
