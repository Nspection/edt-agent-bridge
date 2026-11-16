"""External agent artifacts, cache, bounded workspace guard and lossless migration.
Run with python -B tools/agent_runs.py --help. No third-party dependencies.
"""
from __future__ import annotations
import sys
sys.dont_write_bytecode = True
from contextlib import contextmanager
import argparse, datetime, hashlib, json, os, re, shutil, stat, subprocess, uuid
from pathlib import Path

def filesystem_path(path):
    path=Path(path)
    if os.name=='nt' and not str(path).startswith('\\\\?\\'):
        value=str(path.absolute())
        return Path('\\\\?\\UNC\\'+value[2:] if value.startswith('\\\\') else '\\\\?\\'+value)
    return path

def repository():
    return Path(__file__).resolve().parent.parent

def base(repo=None):
    repo=Path(repo or repository()).resolve();pointer=repo/'.runs'
    if pointer.is_file() and not pointer.is_symlink() and pointer.stat().st_size<=4096:
        target=Path(pointer.read_text(encoding='utf-8-sig').strip())
        if (target/'.agent-run.json').is_file():
            data=marker(target)
            if data.get('repository')==str(repo) and data.get('storage_home'):return external(repo,data['storage_home'])
    return Path(os.environ.get('LOCALAPPDATA', str(Path.home()/'.local/share')))/'edt-agent-bridge'

def external(repo, path):
    path=Path(path).expanduser().resolve();repo=Path(repo).resolve()
    if path==repo or path.is_relative_to(repo) or repo.is_relative_to(path) or path==Path(path.anchor):
        raise ValueError('Agent artifacts must be outside the repository')
    return path

def write_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    temp.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n');os.replace(temp,path)

def marker(path):
    file=Path(path)/'.agent-run.json'
    if file.is_symlink() or file.stat().st_size>8192:raise ValueError('Invalid run marker')
    return json.loads(file.read_text(encoding='utf-8'))

def runs_root(repo=None):
    repo=Path(repo or repository()).resolve();pointer=repo/'.runs'
    override=os.environ.get('AGENT_RUNS_ROOT')
    if override:return external(repo,override)
    if pointer.is_symlink() or pointer.is_dir():raise ValueError('Legacy .runs directory: run agent_runs.py migrate first')
    if pointer.is_file():
        if pointer.stat().st_size>4096:raise ValueError('Invalid .runs pointer')
        return external(repo,pointer.read_text(encoding='utf-8-sig').strip())
    return external(repo,base(repo)/'runs'/(datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d')+'-development'))

def publish_pointer(repo,root):
    pointer=Path(repo)/'.runs'
    if pointer.is_symlink() or getattr(pointer,'is_junction',lambda:False)() or (pointer.exists() and not pointer.is_file()):raise ValueError('Unsafe .runs pointer')
    if pointer.is_file():
        raw=pointer.read_text(encoding='utf-8-sig').strip();old=external(repo,raw)
        if '\n' in raw or not Path(raw).is_absolute() or not (old/'.agent-run.json').is_file() or marker(old).get('repository')!=str(Path(repo).resolve()):raise ValueError('Existing .runs is not a valid owned pointer; preserve it')
    temporary=pointer.with_name('.runs.'+uuid.uuid4().hex+'.tmp')
    temporary.write_text(str(root)+'\n',encoding='utf-8',newline='\n');os.replace(temporary,pointer)

def _initialize(repo=None, task='development', path=None, pinned=False):
    repo=Path(repo or repository()).resolve()
    if not re.fullmatch('[A-Za-z0-9_-]{1,80}',task):raise ValueError('Invalid task name')
    root=external(repo,path or os.environ.get('AGENT_RUNS_ROOT') or base(repo)/'runs'/(datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d')+'-'+task))
    if any((p/'.agent-run.json').exists() for p in root.parents):raise ValueError('Nested run roots are prohibited')
    if root.exists() and any(root.iterdir()) and not (root/'.agent-run.json').is_file():raise ValueError('Refusing to adopt a nonempty unowned directory')
    root.mkdir(parents=True,exist_ok=True)
    file=root/'.agent-run.json'
    if file.exists():
        if marker(root)['repository']!=str(repo):raise ValueError('Run belongs to another repository')
    else:write_json(file,{'repository':str(repo),'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'task':task,'pinned':pinned,'ownership_token':str(uuid.uuid4()),'storage_home':str(base(repo))})
    pointer=repo/'.runs'
    if pointer.exists() and not pointer.is_file():raise ValueError('Migrate legacy .runs first')
    publish_pointer(repo,root);return root

def initialize(repo=None, task='development', path=None, pinned=False):
    repo=Path(repo or repository()).resolve()
    with storage_lock(repo):return _initialize(repo,task,path,pinned)

def cache_root(repo=None):
    return external(repo or repository(),os.environ.get('AGENT_CACHE_ROOT') or base(repo)/'cache')

@contextmanager
def storage_lock(repo):
    lock=cache_root(repo)/'agent-storage.lock';lock.parent.mkdir(parents=True,exist_ok=True)
    with lock.open('a+b') as stream:
        if stream.seek(0,2)==0:stream.write(b'0');stream.flush()
        stream.seek(0)
        if os.name=='nt':
            import msvcrt
            msvcrt.locking(stream.fileno(),msvcrt.LK_LOCK,1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(),fcntl.LOCK_EX)
        try:yield
        finally:
            stream.seek(0)
            if os.name=='nt':msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
            else:fcntl.flock(stream.fileno(),fcntl.LOCK_UN)

@contextmanager
def run_lease(repo,path):
    path=writable_path(repo,path);run=next((p for p in [path,*path.parents] if (p/'.agent-run.json').is_file()),path)
    with storage_lock(repo):
        writable_path(repo,path)
        active=run/'.active';active.mkdir(parents=True,exist_ok=True);token=active/(uuid.uuid4().hex+'.json');write_json(token,{'pid':os.getpid(),'path':str(path)})
    try:yield
    finally:
        with storage_lock(repo):
            token.unlink(missing_ok=True)
            try:active.rmdir()
            except OSError:pass

def artifact_path(repo,value):
    repo=Path(repo).resolve();value=Path(value)
    absolute=value if value.is_absolute() else repo/value
    legacy=repo/'.runs'
    try:relative=absolute.relative_to(legacy)
    except ValueError:return absolute.resolve()
    current=runs_root(repo)/relative
    if current.exists():return current
    locations=cache_root(repo)/'repositories'/hashlib.sha256(str(repo).lower().encode()).hexdigest()[:24]/'legacy-locations.json'
    if locations.exists():
        for old in json.loads(locations.read_text(encoding='utf-8')):
            candidate=external(repo,old)/relative
            if candidate.exists():return candidate
    return current

def writable_path(repo,value):
    path=external(repo,value)
    for parent in [path,*path.parents]:
        if (parent/'.agent-run.json').is_file() and marker(parent).get('pinned'):raise ValueError('Historical run is read-only; create a new run and rebuild')
    return path

def output_path(repo,value):
    repo=Path(repo).resolve();path=Path(value);absolute=path if path.is_absolute() else repo/path
    try:relative=absolute.relative_to(repo/'.runs')
    except ValueError:return writable_path(repo,absolute)
    return writable_path(repo,runs_root(repo)/relative)

def writable_root(repo):
    root=runs_root(repo)
    if (root/'.agent-run.json').exists() and marker(root).get('pinned'):
        if os.environ.get('AGENT_RUNS_ROOT'):raise ValueError('Explicit run root is pinned; select a new run')
        return initialize(repo,'development')
    return initialize(repo,path=root)

def guard(repo=None,max_objects=20000,max_bytes=1024**3):
    repo=Path(repo or repository()).resolve();stack=[repo];objects=size=0
    while stack:
        directory=stack.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                if directory==repo and entry.name=='.git':continue
                objects+=1
                if entry.is_dir(follow_symlinks=False) and not getattr(Path(entry.path),'is_junction',lambda:False)():stack.append(Path(entry.path))
                elif entry.is_file(follow_symlinks=False):size+=entry.stat(follow_symlinks=False).st_size
                if objects>max_objects or size>max_bytes:raise ValueError(f'Workspace artifact guard exceeded: objects>={objects}, bytes>={size}; move artifacts outside repository')
    return {'objects':objects,'bytes':size,'passed':True}

def fixture_root(repo,path):
    root=external(repo,path)
    if root.name!='creation-audit':raise ValueError('Explicit external creation-audit root required')
    return writable_path(repo,root)

def worktrees(repo):
    raw=subprocess.check_output(['git','-C',str(repo),'worktree','list','--porcelain'],text=True)
    return [Path(line[9:]).resolve() for line in raw.splitlines() if line.startswith('worktree ')]

def file_inventory(root, hashes=False):
    root=filesystem_path(root)
    if getattr(root,'is_junction',lambda:False)():raise ValueError('Cannot inventory a junction root')
    stack=[root]
    while stack:
        directory=stack.pop()
        with os.scandir(directory) as entries:
            for entry in entries:
                path=Path(entry.path);s=path.lstat();item={'path':path.relative_to(root).as_posix(),'mode':stat.S_IFMT(s.st_mode)}
                if getattr(path,'is_junction',lambda:False)():raise ValueError('Junction in migration inventory; preserve for review: '+str(path))
                if entry.is_symlink():item.update(kind='symlink',target=os.readlink(path));yield item
                elif entry.is_dir(follow_symlinks=False):item.update(kind='directory');yield item;stack.append(path)
                elif entry.is_file(follow_symlinks=False):
                    item.update(kind='file',size=s.st_size,mtime_ns=s.st_mtime_ns,file_id=s.st_ino,device=s.st_dev)
                    if hashes:
                        h=hashlib.sha256()
                        with path.open('rb') as stream:
                            for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
                        after=path.stat()
                        if (s.st_size,s.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise ValueError('File changed during inventory: '+item['path'])
                        item['sha256']=h.hexdigest()
                    yield item
                else:raise ValueError('Unsupported filesystem entry; preserve for manual review: '+str(path))

def migrate(repo,destination,hashes=False,resume=False):
    repo=Path(repo).resolve();source=repo/'.runs';destination=external(repo,destination)
    if source.drive.lower()!=destination.drive.lower():raise ValueError('Migration requires same-volume atomic rename')
    destination.parent.mkdir(parents=True,exist_ok=True);manifest=destination.with_name(destination.name+'.manifest.jsonl');summary=destination.with_name(destination.name+'.migration.json')
    if resume:
        state=json.loads(summary.read_text(encoding='utf-8'))
        if state['phase']=='verified':return state
        if state['source']!=str(source) or state['destination']!=str(destination) or state['phase'] not in ['inventoried','moved_pending_verification']:raise ValueError('Only a completed inventory can resume; partial inventory is preserved for review')
    else:
        if os.path.lexists(source/'.agent-run.json'):raise ValueError('Reserved root marker already exists; preserve original data')
        if not source.is_dir() or source.is_symlink() or getattr(source,'is_junction',lambda:False)() or destination.exists() or manifest.exists() or summary.exists():raise ValueError('Expected legacy directory and unused migration destination/evidence')
        moved_worktrees=[p for p in worktrees(repo) if p.is_relative_to(source)]
        state={'source':str(source),'destination':str(destination),'manifest':str(manifest),'phase':'inventory','files':0,'directories':0,'bytes':0,'hashes':hashes,'worktrees':[str(p) for p in moved_worktrees]};write_json(summary,state)
        with manifest.open('x',encoding='utf-8',newline='\n') as out:
            for index,item in enumerate(file_inventory(source,hashes)):
                out.write(json.dumps(item,separators=(',',':'))+'\n')
                if item['kind']=='file':state['files']+=1;state['bytes']+=item['size']
                elif item['kind']=='directory':state['directories']+=1
                if index and index%50000==0:print('Inventory',index,'entries',flush=True)
            out.flush();os.fsync(out.fileno())
        state['phase']='inventoried';write_json(summary,state)
    if state['phase']=='inventoried':
        if source.is_dir() and not destination.exists():source.rename(destination)
        elif not destination.is_dir() or source.exists():raise ValueError('Migration filesystem does not match completed inventory')
        state['phase']='moved_pending_verification';write_json(summary,state)
    if source.exists() and not source.is_file():raise ValueError('Unexpected source after move')
    targets=[destination/Path(p).relative_to(source) for p in state['worktrees']]
    if targets:subprocess.run(['git','-C',str(repo),'worktree','repair',*[str(p) for p in targets]],check=True)
    registered=worktrees(repo)
    if repo not in registered or any(p not in registered for p in targets) or any(p.is_relative_to(source) for p in registered):raise ValueError('Git worktree repair verification failed')
    expected=set();git_controls={str((p/'.git').relative_to(destination)).replace('\\','/'):p for p in targets};repaired=[]
    with manifest.open(encoding='utf-8') as stream:
        for index,line in enumerate(stream):
            item=json.loads(line);expected.add(item['path']);path=filesystem_path(destination)/item['path'];info=path.lstat()
            if item['kind']=='file':
                if item['path'] in git_controls:
                    value=path.read_text(encoding='utf-8').strip()
                    if not value.startswith('gitdir: '):raise ValueError('Invalid repaired worktree control file')
                    gitdir=Path(value[8:]).resolve()
                    if not gitdir.is_relative_to(repo/'.git/worktrees'):raise ValueError('Repaired worktree points outside expected Git metadata')
                    common=subprocess.check_output(['git','-C',str(git_controls[item['path']]),'rev-parse','--git-common-dir'],text=True).strip()
                    common=Path(common) if Path(common).is_absolute() else git_controls[item['path']]/common
                    if common.resolve()!=(repo/'.git').resolve():raise ValueError('Repaired worktree common directory changed')
                    backlink=Path((gitdir/'gitdir').read_text(encoding='utf-8').strip()).resolve()
                    if backlink!=(destination/item['path']).resolve():raise ValueError('Repaired worktree backlink does not match this tree')
                    repaired.append(item['path']);continue
                if not path.is_file() or path.is_symlink() or (info.st_size,info.st_mtime_ns,info.st_ino,info.st_dev)!=(item['size'],item['mtime_ns'],item['file_id'],item['device']):raise ValueError('Post-move file identity mismatch: '+item['path'])
                if 'sha256' in item:
                    h=hashlib.sha256()
                    with path.open('rb') as f:
                        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
                    if h.hexdigest()!=item['sha256']:raise ValueError('Post-move content mismatch: '+item['path'])
            elif item['kind']=='directory':
                if not path.is_dir() or path.is_symlink():raise ValueError('Post-move directory mismatch')
            elif not path.is_symlink() or os.readlink(path)!=item['target']:raise ValueError('Post-move symlink mismatch')
            if index and index%50000==0:print('Verified',index,'entries',flush=True)
    actual={i['path'] for i in file_inventory(destination)}
    if state.get('files_verified') and (destination/'.agent-run.json').is_file():
        m=marker(destination)
        if m.get('repository')==str(repo) and m.get('pinned') and m.get('task')=='legacy-migration':actual.discard('.agent-run.json')
    if actual!=expected:raise ValueError('Extra or missing paths after move; preserve all data for review')
    state['files_verified']=True;write_json(summary,state)
    # The only added file is our ownership marker, written after all checks.
    if (destination/'.agent-run.json').exists():
        m=marker(destination)
        if m.get('repository')!=str(repo) or not m.get('pinned') or m.get('task')!='legacy-migration':raise ValueError('Unexpected root marker; original preserved')
    else:write_json(destination/'.agent-run.json',{'repository':str(repo),'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'task':'legacy-migration','pinned':True,'ownership_token':str(uuid.uuid4()),'storage_home':str(base(repo))})
    locations=cache_root(repo)/'repositories'/hashlib.sha256(str(repo).lower().encode()).hexdigest()[:24]/'legacy-locations.json'
    prior=json.loads(locations.read_text(encoding='utf-8')) if locations.exists() else []
    write_json(locations,list(dict.fromkeys([str(destination),*prior])))
    with storage_lock(repo):
        if not source.exists():publish_pointer(repo,destination)
        elif source.is_file():
            current=external(repo,source.read_text(encoding='utf-8-sig').strip())
            if marker(current).get('repository')!=str(repo):raise ValueError('Pointer changed to an unknown owner')
            state['newer_pointer_preserved']=str(current)
        else:raise ValueError('Pointer changed during verification')
    state.update(phase='verified',all_data_file_identities_verified=True,git_control_files_repaired=repaired,all_data_file_sha256_verified=state['hashes'],no_extra_or_missing_paths=True,source_replaced_by_pointer=True,main_git_directory_moved=False,worktrees_verified=len(targets));write_json(summary,state);return state

def _prune_locked(repo, parent,keep,apply=False):
    if keep<1:raise ValueError('Retention must keep at least one run')
    repo=Path(repo).resolve();parent=external(repo,parent)
    def protected(candidate):
        paths=[runs_root(repo),cache_root(repo),*worktrees(repo)]
        pointer=repo/'.runs'
        if pointer.is_file() and not pointer.is_symlink():paths.append(external(repo,pointer.read_text(encoding='utf-8-sig').strip()))
        return any(p==candidate or p.is_relative_to(candidate) for p in paths)
    def contains_protected_descendant(root):
        stack=[root]
        while stack:
            current=stack.pop()
            with os.scandir(current) as entries:
                for e in entries:
                    if e.name=='.git':return True
                    if e.is_dir(follow_symlinks=False):
                        if e.name in ['.git','.active'] or getattr(Path(e.path),'is_junction',lambda:False)():return True
                        stack.append(Path(e.path))
                    elif current!=root and e.name=='.agent-run.json':return True
        return False
    def eligible(p):
        if p.is_symlink() or getattr(p,'is_junction',lambda:False)() or not p.is_dir() or not (p/'.agent-run.json').is_file():return False
        m=marker(p)
        return m.get('repository')==str(repo) and bool(re.fullmatch('[a-f0-9-]{36}',m.get('ownership_token',''))) and not m.get('pinned') and not (p/'.active').exists() and not protected(p.resolve())
    runs=[]
    if parent.exists():
        for p in parent.iterdir():
            if eligible(p):runs.append((marker(p)['created_at'],p))
    current=runs_root(repo);current_counts=current.parent==parent and (current/'.agent-run.json').is_file() and not marker(current).get('pinned')
    candidates=[p for _,p in sorted(runs,reverse=True)[max(0,keep-(1 if current_counts else 0)):]]
    selected=[]
    for p in candidates:
        checked=external(repo,p)
        if checked.parent!=parent or not eligible(p):raise ValueError('Retention target changed')
        if contains_protected_descendant(p):continue
        if apply:shutil.rmtree(filesystem_path(p))
        selected.append(str(p))
    return {'apply':apply,'keep':keep,'paths':selected,'pinned_active_pointer_cache_and_worktrees_preserved':True}

def prune(repo,parent,keep,apply=False):
    with storage_lock(repo):return _prune_locked(repo,parent,keep,apply)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,default=repository());sub=p.add_subparsers(dest='command',required=True)
    sub.add_parser('path');sub.add_parser('output-root');sub.add_parser('cache');s=sub.add_parser('resolve');s.add_argument('value');s=sub.add_parser('new');s.add_argument('--task',required=True);s=sub.add_parser('guard');s.add_argument('--max-objects',type=int,default=20000);s.add_argument('--max-bytes',type=int,default=1024**3);s=sub.add_parser('migrate');s.add_argument('--destination',type=Path,required=True);s.add_argument('--sha256',action='store_true');s.add_argument('--resume',action='store_true');s=sub.add_parser('prune');s.add_argument('--parent',type=Path);s.add_argument('--keep',type=int,default=int(os.environ.get('AGENT_RUNS_KEEP','5')));s.add_argument('--apply',action='store_true')
    a=p.parse_args()
    if a.command=='path':out=str(runs_root(a.repo))
    elif a.command=='output-root':out=str(writable_root(a.repo))
    elif a.command=='cache':out=str(cache_root(a.repo))
    elif a.command=='resolve':out=str(artifact_path(a.repo,a.value))
    elif a.command=='new':out=str(initialize(a.repo,a.task))
    elif a.command=='guard':out=guard(a.repo,a.max_objects,a.max_bytes)
    elif a.command=='migrate':out=migrate(a.repo,a.destination,a.sha256,a.resume)
    else:out=prune(a.repo,a.parent or base(a.repo)/'runs',a.keep,a.apply)
    print(out if isinstance(out,str) else json.dumps(out,indent=2))
if __name__=='__main__':main()
