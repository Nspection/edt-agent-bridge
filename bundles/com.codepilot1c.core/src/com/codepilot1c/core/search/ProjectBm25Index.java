// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded saved-file index. All callers must execute outside SWT; no network or project writes. */
public final class ProjectBm25Index {
    private static final int MAX_FILES=30000,MAX_DOCS=150000,MAX_TERMS=300000,MAX_POSTINGS=8000000;
    private static final long MAX_FILE=8L*1024*1024,MAX_SOURCE=256L*1024*1024,MAX_ESTIMATE=192L*1024*1024;
    private static final java.util.concurrent.Semaphore ACTIVE_SEARCH=new java.util.concurrent.Semaphore(1,true);
    private static final LinkedHashMap<Path,ProjectBm25Index> CACHE=new LinkedHashMap<>(4,.75f,true);
    public static Map<String,Object> searchProject(Path root,String query,String scope,int limit,boolean rebuild)throws Exception {
        ACTIVE_SEARCH.acquire();try {
            Path real=root.toRealPath();ProjectBm25Index index;
            synchronized(CACHE){index=CACHE.computeIfAbsent(real,ProjectBm25Index::new);while(CACHE.size()>2)CACHE.remove(CACHE.keySet().iterator().next());}
            return index.search(query,scope,limit,rebuild);
        } finally {ACTIVE_SEARCH.release();}
    }
    private record Stamp(long size,long modified,String key){}
    private record Candidate(String path,Stamp stamp){}
    private record Source(Stamp stamp,String sha,String caption,String owner,List<Doc> docs){}
    private record Doc(String path,String method,String kind,int start,int line,int end,int length,int[] terms,short[] frequency,boolean truncated,boolean methodTruncated){}
    private record Posting(int[] docs,short[] frequency){}
    private record Scored(int id,double score){}
    private final Path root;private Map<String,Source> sources=Map.of();private final Map<String,Integer> termIds=new HashMap<>();private final List<String> words=new ArrayList<>();
    private List<Doc> documents=List.of();private Posting[] postings=new Posting[0];private String branch="",fingerprint="";private long estimate;private int skipped;private long buildingPairs,buildingDocs,wordBytes,buildingNames;
    public ProjectBm25Index(Path root){this.root=root.toAbsolutePath().normalize();}
    private static final class StaleSource extends IOException {StaleSource(String message){super(message);}}
    private static final class IndexQuota extends IOException {IndexQuota(String message){super(message);}}
    public synchronized Map<String,Object> search(String query,String scope,int limit,boolean rebuild)throws Exception {
        boolean hadIndex=!sources.isEmpty();
        try{return searchOnce(query,scope,limit,rebuild);}catch(IndexQuota e){if(!hadIndex||rebuild)throw e;clear();var result=searchOnce(query,scope,limit,true);result.put("compacted_index",true);return result;}catch(StaleSource e){clear();try{var result=searchOnce(query,scope,limit,true);result.put("retried_source_change",true);return result;}catch(Exception retry){clear();throw retry;}}
    }
    private Map<String,Object> searchOnce(String query,String scope,int limit,boolean rebuild)throws Exception {
        long started=System.nanoTime();if(query==null||query.isBlank()||query.length()>1000)throw new IllegalArgumentException("query must contain 1..1000 characters");
        if(!Set.of("all","modules","queries").contains(scope)||limit<1||limit>20)throw new IllegalArgumentException("scope must be all/modules/queries; top_k 1..20");
        List<String> tokens=new ArrayList<>(new LinkedHashSet<>(Bm25Documents.tokens(query,64)));if(tokens.isEmpty()||tokens.size()>32)throw new IllegalArgumentException("query must contain 1..32 lexical terms");
        int updated;try{updated=update(rebuild);}catch(Exception e){clear();throw e;}
        long built=System.nanoTime();int n=0;long length=0;boolean[] eligible=new boolean[documents.size()];for(int i=0;i<documents.size();i++)if(matches(documents.get(i).path,scope)){eligible[i]=true;n++;length+=documents.get(i).length;}
        double average=n==0?1:(double)length/n;double[] scores=new double[documents.size()];
        for(String token:tokens){Integer term=termIds.get(token);if(term==null||term>=postings.length)continue;Posting p=postings[term];if(p==null)continue;int df=0;for(int id:p.docs)if(eligible[id])df++;if(df==0)continue;double idf=Math.log(1+(n-df+.5)/(df+.5));for(int j=0;j<p.docs.length;j++){int id=p.docs[j];if(!eligible[id])continue;double tf=p.frequency[j];Doc d=documents.get(id);scores[id]+=idf*tf*2.5/(tf+1.5*(.25+.75*d.length/average));}}
        Comparator<Scored> best=(a,b)->{int c=Double.compare(b.score,a.score);if(c==0)c=documents.get(a.id).path.compareTo(documents.get(b.id).path);if(c==0)c=Integer.compare(documents.get(a.id).line,documents.get(b.id).line);return c;};PriorityQueue<Scored> heap=new PriorityQueue<>(limit,best.reversed());int matches=0;
        for(int i=0;i<scores.length;i++)if(scores[i]>0){matches++;heap.add(new Scored(i,scores[i]));if(heap.size()>limit)heap.poll();}
        List<Scored> ordered=new ArrayList<>(heap);ordered.sort(best);List<Map<String,Object>> result=new ArrayList<>();Map<String,String[]> verified=new HashMap<>();
        for(Scored hit:ordered){Doc d=documents.get(hit.id);Source source=sources.get(d.path);String[] lines=verified.get(d.path);if(lines==null){byte[] bytes=read(root.resolve(d.path),source.stamp);if(!sha(bytes).equals(source.sha))throw new StaleSource("Source changed during search");lines=decode(bytes).split("\\R",-1);verified.put(d.path,lines);}
            List<String> matched=new ArrayList<>();for(String token:tokens){Integer id=termIds.get(token);if(id!=null&&Arrays.binarySearch(d.terms,id)>=0)matched.add(token);}
            int anchor=d.line;for(int line=d.start;line<=Math.min(d.end,lines.length);line++){Set<String> lineTokens=new HashSet<>(Bm25Documents.tokens(lines[line-1],256));if(lineTokens.stream().anyMatch(matched::contains)){anchor=line;break;}}
            int from=Math.max(d.start,anchor-1),to=Math.min(Math.min(d.end,lines.length),from+5);String snippet=String.join("\n",Arrays.copyOfRange(lines,from-1,to));boolean snippetTruncated=snippet.length()>1200;if(snippetTruncated)snippet=snippet.substring(0,1200);
            Map<String,Object> row=new LinkedHashMap<>();row.put("file_path",d.path);row.put("file_uri",root.resolve(d.path).toUri().toString());row.put("method",d.method);row.put("kind",d.kind);row.put("line",d.line);row.put("start_line",d.start);row.put("end_line",d.end);row.put("score",hit.score);row.put("matched_terms",matched);row.put("snippet_start_line",from);row.put("snippet",snippet);row.put("snippet_truncated",snippetTruncated);row.put("document_tokens_truncated",d.truncated);row.put("method_name_truncated",d.methodTruncated);result.add(row);
        }
        if(!branch.equals(branchStamp()))throw new StaleSource("Branch changed during search");
        Map<String,Object> out=new LinkedHashMap<>();out.put("search_type","bm25");out.put("query",query);out.put("scope",scope);out.put("results",result);out.put("total_results",matches);out.put("has_more",matches>result.size());out.put("top_k",limit);out.put("path_base","project");out.put("query_terms",tokens);
        Map<String,Object> info=new LinkedHashMap<>();info.put("files",sources.size());info.put("documents",documents.size());info.put("terms",words.size());info.put("updated_files",updated);info.put("reused_files",sources.size()-updated);info.put("estimated_bytes",estimate);info.put("skipped_files",skipped);long truncated=documents.stream().filter(d->d.truncated||d.methodTruncated).count();info.put("truncated_documents",truncated);info.put("partial",skipped>0||truncated>0);info.put("corpus_fingerprint",fingerprint);info.put("branch_context",branch);info.put("freshness","file_metadata_checked");info.put("returned_files_verification","sha256");info.put("atomic_project_snapshot",false);info.put("unsaved_editors_included",false);info.put("refresh_ms",(built-started)/1000000.0);info.put("total_ms",(System.nanoTime()-started)/1000000.0);out.put("index",info);return out;
    }
    private int update(boolean rebuild)throws Exception {
        buildingPairs=0;buildingDocs=0;buildingNames=0;String nowBranch=branchStamp();boolean force=rebuild||!nowBranch.equals(branch)||nowBranch.equals("unavailable");if(force)clear();TreeMap<String,Candidate> files=new TreeMap<>();skipped=0;Path src=root.resolve("src");if(!Files.isDirectory(src,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(src))throw new IOException("Local non-linked src directory required");
        int[] visited={0};long[] bytes={0};Files.walkFileTree(src,new SimpleFileVisitor<>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException {if(Thread.currentThread().isInterrupted())throw new IOException("Search cancelled");if(src.relativize(dir).getNameCount()>64){skipped++;return FileVisitResult.SKIP_SUBTREE;}if(++visited[0]>200000)throw new IOException("BM25 directory limit exceeded");if(!dir.equals(src)&&dir.getFileName().toString().startsWith("."))return FileVisitResult.SKIP_SUBTREE;return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {if(a.isSymbolicLink()){skipped++;return FileVisitResult.CONTINUE;}String path=root.relativize(file).toString().replace('\\','/');if(path.length()>1024){skipped++;return FileVisitResult.CONTINUE;}if(!supported(path)||file.getFileName().toString().startsWith("."))return FileVisitResult.CONTINUE;if(!a.isRegularFile()||a.size()>MAX_FILE){skipped++;return FileVisitResult.CONTINUE;}if(files.size()>=MAX_FILES||(bytes[0]+=a.size())>MAX_SOURCE)throw new IOException("BM25 source quota exceeded (30000 files / 256 MiB)");files.put(path,new Candidate(path,stamp(a)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFileFailed(Path file,IOException e)throws IOException{throw new IOException("Cannot inspect BM25 source file",e);}
        });
        Map<String,Source> next=new TreeMap<>();Map<String,String> captions=new HashMap<>();int updated=0;
        // Metadata captions are dependencies of every module belonging to that object.
        for(var c:files.values())if(c.path.toLowerCase(Locale.ROOT).endsWith(".mdo")){Source old=sources.get(c.path);Source value;if(!force&&old!=null&&old.stamp.equals(c.stamp)){value=old;charge(old.docs);}else{value=parse(c,"");updated++;}next.put(c.path,value);if(mainMetadata(c.path))captions.put(owner(c.path),value.caption);}
        for(var c:files.values())if(!c.path.toLowerCase(Locale.ROOT).endsWith(".mdo")){String caption=captions.getOrDefault(owner(c.path),"");Source old=sources.get(c.path);Source value;if(!force&&old!=null&&old.stamp.equals(c.stamp)&&old.caption.equals(caption)){value=old;charge(old.docs);}else{value=parse(c,caption);updated++;}next.put(c.path,value);}
        if(!nowBranch.equals(branchStamp()))throw new StaleSource("Branch changed during indexing");
        boolean changed=force||updated>0||!next.keySet().equals(sources.keySet());if(changed){rebuild(next);sources=next;branch=nowBranch;fingerprint=fingerprint(next,branch);}return updated;
    }
    private Source parse(Candidate c,String caption)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new IOException("Search cancelled");byte[] data=read(root.resolve(c.path),c.stamp);String text=decode(data);if(text.startsWith("\ufeff"))text=text.substring(1);List<Doc> docs=new ArrayList<>();String object=owner(c.path);String ownCaption=c.path.toLowerCase(Locale.ROOT).endsWith(".mdo")?Bm25Documents.caption(text):caption;
        List<Bm25Documents.Spec> specs=c.path.toLowerCase(Locale.ROOT).endsWith(".bsl")?Bm25Documents.chunks(text):List.of(new Bm25Documents.Spec("",c.path.toLowerCase(Locale.ROOT).endsWith(".mdo")?"metadata":"query",1,1,text.split("\\R",-1).length,c.path.toLowerCase(Locale.ROOT).endsWith(".mdo")?ownCaption:text));
        for(var spec:specs){if(Thread.currentThread().isInterrupted())throw new IOException("Search cancelled");boolean methodTruncated=spec.method().length()>256;String method=methodTruncated?spec.method().substring(0,256):spec.method();Map<Integer,Integer> tf=new HashMap<>();int length=0;boolean truncated=false;List<String> body=Bm25Documents.tokens(spec.content(),Bm25Documents.MAX_TOKENS);if(body.size()>=Bm25Documents.MAX_TOKENS)truncated=true;
            List<String> title=Bm25Documents.tokens(c.path+" "+method+" "+ownCaption,512);for(String term:title){int id=term(term);tf.merge(id,3,Integer::sum);length+=3;}
            for(String term:body){int id=term(term);if(tf.containsKey(id)||tf.size()<Bm25Documents.MAX_TERMS){tf.merge(id,1,Integer::sum);length++;}else truncated=true;}
            int[] ids=tf.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();short[] frequency=new short[ids.length];for(int i=0;i<ids.length;i++)frequency[i]=(short)Math.min(32767,tf.get(ids[i]));charge(ids.length,method.length());docs.add(new Doc(c.path,method,spec.kind(),spec.start(),spec.declaration(),spec.end(),Math.max(length,1),ids,frequency,truncated,methodTruncated));}
        return new Source(c.stamp,sha(data),ownCaption,object,docs);
    }
    private int term(String word)throws IOException{Integer id=termIds.get(word);if(id!=null)return id;if(words.size()>=MAX_TERMS)throw new IndexQuota("BM25 term quota exceeded");int next=words.size();termIds.put(word,next);words.add(word);wordBytes+=100L+word.length()*2L;return next;}
    private void charge(List<Doc> docs)throws IOException{for(Doc d:docs)charge(d.terms.length,d.method.length());}
    private void charge(int terms,int nameLength)throws IOException{buildingDocs++;buildingPairs+=terms;buildingNames+=nameLength*2L;if(buildingDocs>MAX_DOCS||buildingPairs>MAX_POSTINGS||buildingPairs*14+buildingDocs*160+buildingNames+wordBytes>MAX_ESTIMATE)throw new IndexQuota("BM25 index quota exceeded");}
    private void rebuild(Map<String,Source> next)throws IOException {
        List<Doc> docs=new ArrayList<>();long pairs=0;for(Source source:next.values())for(Doc d:source.docs){docs.add(d);pairs+=d.terms.length;}if(docs.size()>MAX_DOCS||pairs>MAX_POSTINGS)throw new IndexQuota("BM25 document/posting quota exceeded");
        estimate=pairs*14L+docs.size()*160L+docs.stream().mapToLong(d->d.method.length()*2L).sum()+next.values().stream().mapToLong(v->v.caption.length()*2L+v.owner.length()*2L+100).sum()+words.stream().mapToLong(w->100L+w.length()*2L).sum();if(estimate>MAX_ESTIMATE)throw new IndexQuota("BM25 estimated index memory quota exceeded (192 MiB)");
        int[] count=new int[words.size()];for(Doc d:docs)for(int term:d.terms)count[term]++;Posting[] index=new Posting[count.length];for(int i=0;i<count.length;i++)if(count[i]>0)index[i]=new Posting(new int[count[i]],new short[count[i]]);Arrays.fill(count,0);
        for(int i=0;i<docs.size();i++){Doc d=docs.get(i);for(int j=0;j<d.terms.length;j++){int t=d.terms[j],at=count[t]++;index[t].docs[at]=i;index[t].frequency[at]=d.frequency[j];}}documents=docs;postings=index;
    }
    private void clear(){sources=Map.of();documents=List.of();postings=new Posting[0];termIds.clear();words.clear();branch="";fingerprint="";estimate=0;wordBytes=0;}
    private static Stamp stamp(BasicFileAttributes a){return new Stamp(a.size(),a.lastModifiedTime().to(TimeUnit.NANOSECONDS),String.valueOf(a.fileKey()));}
    private byte[] read(Path file,Stamp expected)throws IOException {if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(root.toRealPath()))throw new IOException("Linked source outside project");try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] data=in.readNBytes((int)MAX_FILE+1);if(data.length>MAX_FILE||data.length!=expected.size||!stamp(Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS)).equals(expected))throw new StaleSource("Source changed while reading");return data;}}
    private static boolean supported(String path){String s=path.toLowerCase(Locale.ROOT);return s.endsWith(".bsl")||s.endsWith(".mdo")||s.endsWith(".query")||s.endsWith(".sdbl");}
    private static boolean matches(String path,String scope){String s=path.toLowerCase(Locale.ROOT);return scope.equals("all")||scope.equals("modules")&&s.endsWith(".bsl")||scope.equals("queries")&&(s.endsWith(".query")||s.endsWith(".sdbl")||s.contains("/queries/")||s.contains("/query"));}
    private static String owner(String path){String[] p=path.split("/");return String.join("/",Arrays.copyOf(p,Math.min(3,p.length-1)));}
    private static boolean mainMetadata(String path){String[] p=path.split("/");return p.length==4&&p[3].equalsIgnoreCase(p[2]+".mdo");}
    private static String sha(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String fingerprint(Map<String,Source> sources,String branch){StringBuilder s=new StringBuilder(branch);sources.forEach((p,v)->s.append('\n').append(p).append(':').append(v.sha));return sha(s.toString().getBytes(StandardCharsets.UTF_8));}
    private String branchStamp(){try{Path dir=root;for(int depth=0;dir!=null&&depth<16;depth++,dir=dir.getParent()){
        Path git=dir.resolve(".git");if(!Files.exists(git,LinkOption.NOFOLLOW_LINKS))continue;if(Files.isSymbolicLink(git))return "unavailable";
        if(Files.isRegularFile(git,LinkOption.NOFOLLOW_LINKS)){String pointer=bounded(git,4096).trim();if(!pointer.startsWith("gitdir: "))return "unavailable";git=dir.resolve(pointer.substring(8).trim()).normalize();}
        if(network(git)||!Files.isDirectory(git,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(git))return "unavailable";
        Path common=git;if(Files.isRegularFile(git.resolve("commondir"),LinkOption.NOFOLLOW_LINKS))common=git.resolve(bounded(git.resolve("commondir"),4096).trim()).normalize();
        if(network(common)||!Files.isDirectory(common,LinkOption.NOFOLLOW_LINKS))return "unavailable";
        String head=bounded(git.resolve("HEAD"),4096),ref="";if(head.startsWith("ref: ")){String name=head.substring(5).trim();if(!name.startsWith("refs/heads/")||name.contains("..")||name.contains("\\")||name.chars().anyMatch(c->c<=32||"~^:?*[".indexOf(c)>=0))return "unavailable";Path loose=common.resolve(name).normalize();if(!loose.startsWith(common))return "unavailable";ref=Files.exists(loose)?bounded(loose,4096):bounded(common.resolve("packed-refs"),4*1024*1024);}
        return sha((head+"\n"+ref).getBytes(StandardCharsets.UTF_8));}return "no_git";}catch(Exception e){return "unavailable";}}

    private static String decode(byte[] bytes)throws IOException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
    private static boolean network(Path p){String s=p.toAbsolutePath().toString();return s.startsWith(Character.toString(92).repeat(2))||s.startsWith("//");}
    private static String bounded(Path p,int max)throws IOException {try(var in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){byte[] b=in.readNBytes(max+1);if(b.length>max)throw new IOException("Git context too large");return new String(b,StandardCharsets.UTF_8);}}
}
