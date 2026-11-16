"""Regression tests for external storage safety; all fixtures live in system temp."""
import sys
sys.dont_write_bytecode=True
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import agent_runs as a
import json,os,tempfile,unittest,shutil
from unittest.mock import patch
class StorageTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name);self.repo=self.base/'repo';self.repo.mkdir();self.local=self.base/'local';self.env=patch.dict(os.environ,{'LOCALAPPDATA':str(self.local)},clear=False);self.env.start();self.addCleanup(self.env.stop);self.saved={k:os.environ.pop(k,None) for k in ['AGENT_RUNS_ROOT','AGENT_CACHE_ROOT']};self.addCleanup(self.restore)
 def restore(self):
  for k,v in self.saved.items():
   os.environ.pop(k,None)
   if v is not None:os.environ[k]=v
 def test_pointer_env_and_inside_rejection(self):
  run=a.initialize(self.repo,'one');self.assertEqual(run,a.runs_root(self.repo));self.assertTrue((self.repo/'.runs').is_file());os.environ['AGENT_RUNS_ROOT']=str(self.base/'override');self.assertEqual(self.base/'override',a.runs_root(self.repo));os.environ['AGENT_RUNS_ROOT']=str(self.repo/'bad');self.assertRaises(ValueError,a.runs_root,self.repo)
 def test_legacy_directory_fails_without_mutation(self):
  (self.repo/'.runs').mkdir();self.assertRaises(ValueError,a.runs_root,self.repo);self.assertTrue((self.repo/'.runs').is_dir())
 def test_guard_skips_root_git_and_stops_at_threshold(self):
  (self.repo/'.git').mkdir();(self.repo/'.git/huge').write_bytes(b'x'*100);(self.repo/'one').write_text('x');self.assertEqual(1,a.guard(self.repo,1,1)['objects']);(self.repo/'two').write_text('x');self.assertRaises(ValueError,a.guard,self.repo,1,100)
 def test_migration_preserves_every_byte_and_repairs_only_registered_paths(self):
  source=self.repo/'.runs';(source/'pages').mkdir(parents=True);(source/'pages/data').write_bytes(bytes(range(256)));(source/'note').write_text('hello');destination=self.local/'migration';calls=[]
  with patch.object(a,'worktrees',side_effect=[[self.repo,source/'pages'],[self.repo,destination/'pages']]),patch.object(a.subprocess,'run',side_effect=lambda args,**kw:calls.append(args)):
   result=a.migrate(self.repo,destination)
  self.assertTrue(result['all_data_file_identities_verified']);self.assertEqual(bytes(range(256)),(destination/'pages/data').read_bytes());self.assertEqual(destination,a.runs_root(self.repo));self.assertTrue(a.marker(destination)['pinned']);self.assertEqual(destination/'pages/data',a.artifact_path(self.repo,'.runs/pages/data'));self.assertEqual('repair',calls[0][4]);self.assertEqual(str(destination/'pages'),calls[0][-1]);self.assertFalse((self.repo/'.git').exists())
  a.initialize(self.repo,'new');self.assertEqual(destination/'pages/data',a.artifact_path(self.repo,source/'pages/data'))
 def test_prune_dry_run_keeps_current_pinned_foreign_and_worktrees(self):
  parent=self.local/'runs';runs=[]
  for i in range(8):
   r=a.initialize(self.repo,'run'+str(i),parent/('run'+str(i)));m=a.marker(r);m['created_at']=str(i);a.write_json(r/'.agent-run.json',m);runs.append(r)
  m=a.marker(runs[0]);m['pinned']=True;a.write_json(runs[0]/'.agent-run.json',m)
  (runs[1]/'.active').mkdir();foreign=parent/'foreign';foreign.mkdir();a.write_json(foreign/'.agent-run.json',{'repository':'elsewhere','created_at':'0','pinned':False})
  with patch.object(a,'worktrees',return_value=[self.repo,runs[2]/'pages']):
   plan=a.prune(self.repo,parent,2);self.assertEqual({str(runs[3]),str(runs[4]),str(runs[5])},set(plan['paths']));self.assertTrue(runs[3].exists());a.prune(self.repo,parent,2,True)
  self.assertFalse(runs[3].exists());self.assertTrue(all(r.exists() for r in [runs[0],runs[1],runs[2],runs[6],runs[7],foreign]))
 def test_bad_task_cannot_escape(self):
  self.assertRaises(ValueError,a.initialize,self.repo,'../bad');self.assertRaises(ValueError,a.fixture_root,self.repo,self.repo/'creation-audit')
 def test_refuses_adoption_of_nonempty_user_directory_and_ancestors(self):
  folder=self.base/'personal';folder.mkdir();(folder/'data').write_text('keep');self.assertRaises(ValueError,a.initialize,self.repo,'bad',folder);self.assertFalse((folder/'.agent-run.json').exists());self.assertRaises(ValueError,a.external,self.repo,self.base)
 def test_nested_new_runs_are_rejected_and_pointer_is_protected(self):
  parent=self.local/'runs';old=a.initialize(self.repo,'old',parent/'old');self.assertRaises(ValueError,a.initialize,self.repo,'nested',old/'nested');other=a.initialize(self.repo,'other',parent/'other');os.environ['AGENT_RUNS_ROOT']=str(old)
  with patch.object(a,'worktrees',return_value=[self.repo]):self.assertEqual([],a.prune(self.repo,parent,1)['paths'])
 def test_preexisting_nested_ownership_blocks_retention(self):
  parent=self.local/'runs';old=a.initialize(self.repo,'old',parent/'old');nested=old/'nested';nested.mkdir();a.write_json(nested/'.agent-run.json',{'repository':'another'});a.initialize(self.repo,'current',parent/'current')
  with patch.object(a,'worktrees',return_value=[self.repo]):self.assertEqual([],a.prune(self.repo,parent,1,True)['paths'])
  self.assertTrue(nested.exists())
 def test_migration_never_overwrites_original_reserved_marker(self):
  source=self.repo/'.runs';source.mkdir();(source/'.agent-run.json').write_text('original');self.assertRaises(ValueError,a.migrate,self.repo,self.local/'legacy');self.assertEqual('original',(source/'.agent-run.json').read_text())
 def test_pinned_history_never_accepts_output_and_lookup_is_read_only(self):
  history=a.initialize(self.repo,'history',self.local/'history',pinned=True);(history/'data').write_text('evidence');self.assertEqual(history/'data',a.artifact_path(self.repo,'.runs/data'));self.assertRaises(ValueError,a.output_path,self.repo,'.runs/data');self.assertRaises(ValueError,a.writable_path,self.repo,history/'data');self.assertEqual('evidence',(history/'data').read_text())
 def test_pointer_symlink_is_not_followed(self):
  target=self.base/'keep';target.write_text('keep')
  try:(self.repo/'.runs').symlink_to(target)
  except OSError:self.skipTest('symlink not available')
  self.assertRaises(ValueError,a.initialize,self.repo,'one');self.assertEqual('keep',target.read_text())
 def test_interrupted_move_resumes_saved_inventory_without_data_loss(self):
  source=self.repo/'.runs';source.mkdir();(source/'data').write_text('keep');dest=self.local/'migration'
  with patch.object(a,'worktrees',side_effect=[[self.repo],[self.repo]]),patch.object(a,'file_inventory',wraps=a.file_inventory) as inv,patch.object(a,'publish_pointer',side_effect=OSError('interrupted')):
   self.assertRaises(OSError,a.migrate,self.repo,dest)
  # Pointer publication is last, all evidence/files are still at the destination.
  self.assertEqual('keep',(dest/'data').read_text());self.assertFalse(source.exists())
  with patch.object(a,'worktrees',return_value=[self.repo]):result=a.migrate(self.repo,dest,resume=True)
  self.assertTrue(result['all_data_file_identities_verified']);self.assertEqual(dest,a.runs_root(self.repo))
 def test_active_lease_survives_pointer_change_and_blocks_prune(self):
  parent=self.local/'runs';old=a.initialize(self.repo,'old',parent/'old')
  with a.run_lease(self.repo,old/'build'):
   a.initialize(self.repo,'new',parent/'new')
   with patch.object(a,'worktrees',return_value=[self.repo]):self.assertEqual([],a.prune(self.repo,parent,1)['paths'])
  with patch.object(a,'worktrees',return_value=[self.repo]):self.assertEqual([str(old)],a.prune(self.repo,parent,1)['paths'])
 def test_extended_windows_paths_keep_real_file_identity(self):
  root=self.local/'deep';self.addCleanup(shutil.rmtree,a.filesystem_path(root));folder=a.filesystem_path(root)
  for i in range(9):folder=folder/('segment-'+str(i)+'-'+'x'*22)
  folder.mkdir(parents=True);(folder/'data').write_bytes(b'kept')
  files=[v for v in a.file_inventory(root) if v['kind']=='file'];self.assertEqual(1,len(files));self.assertEqual(4,files[0]['size']);self.assertNotEqual(0,files[0]['file_id'])
 def test_foreign_linked_worktree_gitfile_blocks_retention(self):
  parent=self.local/'runs';old=a.initialize(self.repo,'old',parent/'old');tree=old/'foreign';tree.mkdir();(tree/'.git').write_text('gitdir: elsewhere');(tree/'unsaved').write_text('user work');a.initialize(self.repo,'current',parent/'current')
  with patch.object(a,'worktrees',return_value=[self.repo]):self.assertEqual([],a.prune(self.repo,parent,1,True)['paths'])
  self.assertEqual('user work',(tree/'unsaved').read_text())
 def test_pointer_keeps_storage_home_when_sandbox_redirects_localappdata(self):
  run=a.initialize(self.repo,'one');before=a.cache_root(self.repo);os.environ['LOCALAPPDATA']=str(self.base/'sandbox')
  self.assertEqual(before,a.cache_root(self.repo));self.assertEqual(run,a.runs_root(self.repo));next_run=a.initialize(self.repo,'two');self.assertEqual(run.parent,next_run.parent)
if __name__=='__main__':unittest.main()
