// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;

import java.util.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.common.util.EMap;
import com._1c.g5.v8.dt.moxel.*;
import com._1c.g5.v8.dt.mcore.Color;

/** Property-level three-way merge of native models. Pool indexes are expanded before comparing.
 * Coordinate shifts are conflicts, never guessed into rows/areas/drawings.
 */
public final class SpreadsheetSemanticMerge {
    private static final Set<String> POOLS=Set.of("formats","fonts","colors","lines","pictures");
    private static final int MAX_NODES=100000,MAX_DEPTH=80;
    public record Difference(String id,String path,int row,int col,String origin,boolean conflict,
            boolean resolved,String resolution,String ancestor,String main,String other,String result) {}
    public record Analysis(SpreadsheetDocument result,List<Difference> differences,boolean threeWay) {
        public long unresolved(){return differences.stream().filter(d->d.conflict()&&!d.resolved()).count();}
    }
    private SpreadsheetSemanticMerge() {}
    private static final class Value {
        String cachedKey; final String kind; EClass type; Object atom; final Map<String,Value> fields=new TreeMap<>();
        final Map<String,Object> keys=new HashMap<>(); final List<Value> items=new ArrayList<>();
        Value(String kind){this.kind=kind;}
        String key(){if(cachedKey==null)cachedKey=computeKey();return cachedKey;}
        private String computeKey(){
            var out=new StringBuilder();part(out,kind);
            if(kind.equals("atom")){
                if(atom instanceof Color c){part(out,c.eClass().getEPackage().getNsURI());part(out,c.eClass().getName());part(out,String.valueOf(c.compositeId()));}
                else if(atom instanceof byte[] b){part(out,"byte[]");part(out,Base64.getEncoder().encodeToString(b));}
                else {part(out,atom==null?"null":atom.getClass().getName());part(out,String.valueOf(atom));}
            }else{
                part(out,type==null?"":type.getEPackage().getNsURI()+":"+type.getName());part(out,String.valueOf(atom));
                var properties=fields.entrySet().stream().filter(e->!kind.equals("object")||!e.getKey().equals("columnsId")).toList();part(out,Integer.toString(properties.size()));
                for(var entry:properties){part(out,entry.getKey());part(out,entry.getValue().key());}
                part(out,Integer.toString(items.size()));for(var item:items)part(out,item.key());
            }
            return out.toString();
        }
        private static void part(StringBuilder out,String value){out.append(value.length()).append(':').append(value);}

    }
    private static Value atom(Object v){var n=new Value("atom");n.atom=v;return n;}
    private static boolean same(Value a,Value b){return a==b || a!=null&&b!=null&&a.key().equals(b.key());}
    private static String pool(EObject owner,String name){
        if(name.equals("pictureIndex"))return "pictures";
        if(name.equals("formatIndex")||name.equals("defaultFormatIndex"))return "formats";
        if(owner instanceof Format){
            if(name.equals("font"))return "fonts";
            if(Set.of("leftBorder","topBorder","rightBorder","bottomBorder","drawingBorder").contains(name))return "lines";
            if(name.endsWith("Color"))return "colors";
            if(name.equals("pictureIndex"))return "pictures";
        }
        if(owner instanceof SpreadsheetDocument&&name.equals("backPicture"))return "pictures";
        return null;
    }
    private static Value encode(Object object,SpreadsheetDocument doc,int depth,int[] count){return encode(object,doc,depth,count,false);}
    private static Value encode(Object object,SpreadsheetDocument doc,int depth,int[] count,boolean bandDefinition){
        if(depth>MAX_DEPTH||++count[0]>MAX_NODES)throw new IllegalArgumentException("Spreadsheet semantic model exceeds bounded traversal");
        if(object instanceof Columns columns&&!bandDefinition){var ref=new Value("column_ref");ref.atom=bandId(columns);return ref;}
        if(object instanceof EMap<?,?> map){var n=new Value("map");for(var e:map){if(e.getValue() instanceof Row row&&ignorableRow(row,doc))continue;String k=e.getKey().getClass().getName()+":"+e.getKey();n.keys.put(k,e.getKey());n.fields.put(k,encode(e.getValue(),doc,depth+1,count));}return n;}
        if(object instanceof Collection<?> list){var n=new Value("list");for(var e:list)n.items.add(encode(e,doc,depth+1,count));return n;}
        if(object instanceof Color)return atom(object); // Immutable native/style color values must not be EcoreUtil-copied.
        if(!(object instanceof EObject o))return atom(object);
        var n=new Value("object");n.type=o.eClass();
        for(var f:o.eClass().getEAllStructuralFeatures()){
            if(o instanceof SpreadsheetDocument sheet&&f.getName().equals("allColumns")){
                var bands=new Value("map");var values=new ArrayList<Columns>(sheet.getAllColumns());if(sheet.getColumns()!=null)values.add(sheet.getColumns());for(var row:sheet.getRows())if(row.getValue().getColumns()!=null)values.add(row.getValue().getColumns());
                for(var band:values){String key="band:"+bandId(band);bands.keys.put(key,bandId(band));bands.fields.put(key,encode(band,doc,depth+1,count,true));}if(!bands.fields.isEmpty())n.fields.put("allColumns",bands);continue;
            }
            if(f.isDerived()||f.isTransient()||!f.isChangeable()||!o.eIsSet(f)&&!Set.of("formatIndex","defaultFormatIndex","columnsId").contains(f.getName()))continue;
            if(o instanceof SpreadsheetDocument&&POOLS.contains(f.getName()))continue;
            Object v=o.eGet(f);String pool=pool(o,f.getName());
            if(pool!=null&&v instanceof Number number){
                var list=(List<?>)doc.eGet(doc.eClass().getEStructuralFeature(pool));int index=number.intValue();
                if(index>=0&&index<list.size()){var ref=new Value("pool");ref.atom=pool;ref.items.add(encode(list.get(index),doc,depth+1,count));n.fields.put(f.getName(),ref);continue;}
            }
            n.fields.put(f.getName(),encode(v,doc,depth+1,count));
        }
        return n;
    }
    private static Object decode(Value n,SpreadsheetDocument doc){return decode(n,doc,new HashMap<>());}
    private static Object decode(Value n,SpreadsheetDocument doc,Map<String,EObject> shared){
        if(n==null)return null;
        if(n.kind.equals("column_ref")){var column=shared.get(String.valueOf(n.atom));if(column==null)throw new IllegalArgumentException("Missing column band: "+n.atom);return column;}
        if(n.kind.equals("atom"))return n.atom instanceof Color color?copyColor(color,0):n.atom;
        if(n.kind.equals("pool")){
            @SuppressWarnings("unchecked") var pool=(List<Object>)doc.eGet(doc.eClass().getEStructuralFeature((String)n.atom));
            String key=n.items.get(0).key();
            for(int i=0;i<pool.size();i++)if(encode(pool.get(i),doc,0,new int[1]).key().equals(key))return i;
            Object value=decode(n.items.get(0),doc,shared);pool.add(value);return pool.size()-1;
        }
        if(n.kind.equals("list")){var out=new ArrayList<>();for(var item:n.items)out.add(decode(item,doc,shared));return out;}
        if(n.kind.equals("map")){var out=new LinkedHashMap<>();for(var entry:n.fields.entrySet())out.put(n.keys.get(entry.getKey()),decode(entry.getValue(),doc,shared));return out;}
        if(n.type.getName().equals("Columns")&&shared.containsKey(columnIdentity(n)))return shared.get(columnIdentity(n));
        EObject out=n.type.getEPackage().getEFactoryInstance().create(n.type);
        if(n.type.getName().equals("Columns")){shared.put(columnIdentity(n),out);if(doc!=null)doc.getAllColumns().add((Columns)out);}
        if(out instanceof SpreadsheetDocument d){doc=d;doc.getFormats().add(MoxelFactory.eINSTANCE.createFormat());}
        for(var e:n.fields.entrySet()){
            var f=out.eClass().getEStructuralFeature(e.getKey());Object v=decode(e.getValue(),doc,shared);Object target=out.eGet(f);
            if(out instanceof SpreadsheetDocument&&f.getName().equals("allColumns")&&v instanceof Map<?,?> bands){out.eSet(f,new ArrayList<>(bands.values()));}
            else if(target instanceof EMap map&&v instanceof Map values){map.putAll(values);}
            else out.eSet(f,v);
        }
        return out;
    }
    private static String bandId(Columns columns){return columns.getColumnsId()==null?"default":columns.getColumnsId().toString();}
    private static String columnIdentity(Value value){var id=value.fields.get("columnsId");return id==null||id.atom==null?"default":String.valueOf(id.atom);}
    private static Color copyColor(Color value,int depth){
        if(depth>16)throw new IllegalArgumentException("Cyclic native color reference");
        var copy=(Color)value.eClass().getEPackage().getEFactoryInstance().create(value.eClass());
        for(var f:value.eClass().getEAllAttributes())if(!f.isDerived()&&!f.isTransient()&&f.isChangeable()&&value.eIsSet(f))copy.eSet(f,value.eGet(f));
        if(value instanceof com._1c.g5.v8.dt.mcore.ColorDef source&&copy instanceof com._1c.g5.v8.dt.mcore.ColorDef target){target.setRed(source.getRed());target.setGreen(source.getGreen());target.setBlue(source.getBlue());}
        if(value instanceof com._1c.g5.v8.dt.mcore.ColorRef source&&copy instanceof com._1c.g5.v8.dt.mcore.ColorRef target&&source.getColor()!=null)target.setColor(copyColor(source.getColor(),depth+1));
        if(value instanceof com._1c.g5.v8.dt.mcore.StyleColor source&&copy instanceof com._1c.g5.v8.dt.mcore.StyleColor target)target.setCompositeId(source.getCompositeId());
        if(value instanceof com._1c.g5.v8.dt.mcore.PaletteColor source&&copy instanceof com._1c.g5.v8.dt.mcore.PaletteColor target)target.setCompositeId(source.getCompositeId());
        return copy;
    }
    private static boolean ignorableRow(Row row,SpreadsheetDocument doc){
        if(!row.getCells().isEmpty()||row.getColumns()!=null&&(doc.getColumns()==null||!bandId(row.getColumns()).equals(bandId(doc.getColumns()))))return false;
        int index=row.getFormatIndex();if(index<0||index>=doc.getFormats().size())return false;
        var format=doc.getFormats().get(index);return format.eClass().getEAllStructuralFeatures().stream().noneMatch(f->!f.isDerived()&&!f.isTransient()&&format.eIsSet(f));
    }
    private static Value snapshot(SpreadsheetDocument d){return d==null?null:encode(d,d,0,new int[1]);}
    public static Analysis analyze(SpreadsheetDocument ancestor,SpreadsheetDocument main,SpreadsheetDocument other,
            SpreadsheetDocument current,Map<String,String> decisions,boolean automatic){
        return analyze(ancestor,main,other,current,decisions,automatic,decisions.keySet());
    }
    public static Analysis analyze(SpreadsheetDocument ancestor,SpreadsheetDocument main,SpreadsheetDocument other,
            SpreadsheetDocument current,Map<String,String> decisions,boolean automatic,Set<String> applyIds){
        var b=snapshot(ancestor);var m=snapshot(main);var o=snapshot(other);var r=snapshot(current==null?main:current);
        var differences=new ArrayList<Difference>();
        if(structuralShift(main,other)||ancestor!=null&&(structuralShift(ancestor,main)||structuralShift(ancestor,other))||current!=null&&structuralShift(main,current)){
            String id=id("/structure");String choice=decisions.get(id);if("COMMON_ANCESTOR".equals(choice)&&b==null)throw new IllegalArgumentException("No common ancestor");Value selected=applyIds.contains(id)?choose(choice,b,m,o,r):r;
            differences.add(new Difference(id,"/structure",0,0,"STRUCTURE",true,choice!=null,choice,"Общий предок","Текущая структура","Структура источника","Требуется решение по структуре целиком"));
            return new Analysis((SpreadsheetDocument)decode(selected==null?r:selected,null),List.copyOf(differences),b!=null);
        }
        Value merged=merge("",b,m,o,r,decisions,automatic,differences,b!=null,applyIds);
        retainReferencedBands(merged,r,m,o,b);
        return new Analysis((SpreadsheetDocument)decode(merged,null),List.copyOf(differences),b!=null);
    }
    /** Whole-document modes rebuild from source snapshots; callers must confirm replacing manual edits. */
    public record ModeResult(Analysis analysis,Map<String,String> decisions) {}
    public static ModeResult applyMode(SpreadsheetDocument ancestor,SpreadsheetDocument main,SpreadsheetDocument other,String mode){
        if(mode==null||!Set.of("MAIN","OTHER","PRIORITY_MAIN","PRIORITY_OTHER").contains(mode))throw new IllegalArgumentException("Unknown spreadsheet merge mode");
        boolean priority=mode.startsWith("PRIORITY_");String side=mode.endsWith("OTHER")?"OTHER":"MAIN";
        var automatic=analyze(ancestor,main,other,main,Map.of(),priority);
        var decisions=new TreeMap<String,String>();
        for(var d:automatic.differences())if(d.conflict()&&(!priority||!d.path().equals("/structure")))decisions.put(d.id(),side);
        var current=priority?automatic.result():side.equals("MAIN")?main:other;
        var result=analyze(ancestor,main,other,current,decisions,false,priority?decisions.keySet():Set.of());
        return new ModeResult(result,Map.copyOf(decisions));
    }
    private static void retainReferencedBands(Value document,Value... sources){
        var refs=new HashSet<String>();collectBandRefs(document,refs);if(refs.isEmpty())return;
        var bands=document.fields.computeIfAbsent("allColumns",k->new Value("map"));
        for(String id:refs){String key="band:"+id;if(bands.fields.containsKey(key))continue;
            for(Value source:sources)if(source!=null&&source.fields.containsKey("allColumns")&&source.fields.get("allColumns").fields.containsKey(key)){
                bands.fields.put(key,source.fields.get("allColumns").fields.get(key));bands.keys.put(key,id);break;
            }
            if(!bands.fields.containsKey(key))throw new IllegalArgumentException("Referenced column band has no definition: "+id);
        }
    }
    private static void collectBandRefs(Value value,Set<String> out){if(value==null)return;if(value.kind.equals("column_ref"))out.add(String.valueOf(value.atom));for(var child:value.fields.values())collectBandRefs(child,out);for(var child:value.items)collectBandRefs(child,out);}
    private static Value choose(String choice,Value b,Value m,Value o,Value r){
        if(choice==null)return null;
        return switch(choice){case "MAIN"->m;case "OTHER"->o;case "COMMON_ANCESTOR"->b;case "RESULT"->r;default->throw new IllegalArgumentException("Unknown spreadsheet resolution: "+choice);};
    }
    private static Value merge(String path,Value b,Value m,Value o,Value r,Map<String,String> choices,boolean auto,List<Difference> diffs,boolean three,Set<String> applyIds){
        if(path.endsWith("/columnsId"))return r;
        if(same(b,m)&&same(m,o)&&same(m,r))return r;
        if(m!=null&&o!=null&&r!=null&&m.kind.equals("pool")&&o.kind.equals("pool")&&r.kind.equals("pool")&&Objects.equals(m.atom,o.atom)&&Objects.equals(m.atom,r.atom)){
            var out=new Value("pool");out.atom=m.atom;out.items.add(merge(path,b!=null&&b.kind.equals("pool")?b.items.get(0):null,m.items.get(0),o.items.get(0),r.items.get(0),choices,auto,diffs,three,applyIds));return out;
        }
        boolean recurse=m!=null&&o!=null&&r!=null&&m.kind.equals(o.kind)&&m.kind.equals(r.kind)
            &&(m.kind.equals("object")&&m.type==o.type&&m.type==r.type||m.kind.equals("map"))
            &&(b==null||b.kind.equals(m.kind))&&!(three&&b==null&&m.kind.equals("object")&&!same(m,o));
        if(recurse){
            var out=new Value(m.kind);out.type=m.type;out.keys.putAll(m.keys);out.keys.putAll(o.keys);out.keys.putAll(r.keys);if(b!=null)out.keys.putAll(b.keys);
            var keys=new TreeSet<String>();keys.addAll(m.fields.keySet());keys.addAll(o.fields.keySet());keys.addAll(r.fields.keySet());if(b!=null)keys.addAll(b.fields.keySet());
            for(String key:keys){Value v=merge(path+"/"+key,b==null?null:b.fields.get(key),m.fields.get(key),o.fields.get(key),r.fields.get(key),choices,auto,diffs,three,applyIds);if(v!=null)out.fields.put(key,v);}return out;
        }
        if(three&&auto&&(path.equals("/height")||path.equals("/columns/size"))&&b!=null&&m!=null&&o!=null&&b.atom instanceof Number bn&&m.atom instanceof Number mn&&o.atom instanceof Number on&&mn.intValue()>=bn.intValue()&&on.intValue()>=bn.intValue()&&same(r,m)){
            Value extent=atom(Math.max(mn.intValue(),on.intValue()));if(!same(extent,m))diffs.add(new Difference(id(path),path,0,0,"OTHER",false,true,null,summary(b),summary(m),summary(o),summary(extent)));return extent;
        }
        boolean sourceDiff=!same(m,o),manual=!same(r,m)&&!same(r,o);boolean conflict=sourceDiff&&(!three||!same(b,m)&&!same(b,o));
        Value proposed=same(m,o)?m:three&&same(m,b)?o:m;
        String choice=choices.get(id(path));Value selected=choose(choice,b,m,o,r);
        if(choice!=null&&choice.equals("COMMON_ANCESTOR")&&!three)throw new IllegalArgumentException("No common ancestor");
        boolean userChanged=!same(r,m);boolean resolved=choice!=null||conflict&&userChanged;
        Value result=choice!=null&&applyIds.contains(id(path))?selected:auto&&!userChanged&&!conflict?proposed:r;
        if(sourceDiff||!same(r,m)){
            String origin=manual?"MANUAL":three&&same(m,b)&&!same(o,b)?"OTHER":three&&same(o,b)&&!same(m,b)?"MAIN":same(result,o)&&!same(result,m)?"OTHER":same(result,m)&&!same(m,b)?"MAIN":"SHARED";
            if(conflict&&!resolved)origin="CONFLICT";
            int[] position=position(path);
            diffs.add(new Difference(id(path),path,position[0],position[1],origin,conflict,resolved,choice,summary(b),summary(m),summary(o),summary(result)));
        }
        return result;
    }
    public static String id(String path){return EdtComparisonService.hash(path);}
    private static String summary(Value value){return describe(value,0);}
    private static String describe(Value value,int depth){
        if(value==null)return "∅";
        if(value.kind.equals("atom")){String s=value.atom instanceof byte[] b?"Двоичные данные ("+b.length+" байт)":String.valueOf(value.atom);return s.length()>300?s.substring(0,300)+"…":s;}
        if(value.kind.equals("column_ref"))return "Полоса колонок";
        if(value.kind.equals("pool"))return describe(value.items.get(0),depth);
        if(value.kind.equals("object")&&value.type.getName().equals("LocalString")){var content=value.fields.get("content");return content==null?"":content.fields.entrySet().stream().map(e->e.getKey().substring(e.getKey().lastIndexOf(':')+1)+": "+describe(e.getValue(),0)).reduce((a,b)->a+"; "+b).orElse("");}
        if(value.kind.equals("object")&&value.type.getName().equals("Row")&&value.fields.containsKey("cells"))return value.fields.get("cells").fields.entrySet().stream().limit(4).map(e->"C"+(Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf(':')+1))+1)+": "+describe(e.getValue(),0)).reduce((a,b)->a+"; "+b).orElse("Пустая строка");
        if(depth>3)return value.kind.equals("object")?value.type.getName():"…";
        if(value.kind.equals("list"))return "["+value.items.stream().limit(3).map(v->describe(v,depth+1)).reduce((a,b)->a+"; "+b).orElse("")+(value.items.size()>3?"; …":"")+"]";
        if(value.kind.equals("object")&&value.fields.containsKey("text"))return describe(value.fields.get("text"),depth+1);
        if(value.kind.equals("object")&&value.type.getName().equals("LocalString"))return describe(value.fields.get("content"),depth+1);
        String s=value.fields.entrySet().stream().filter(e->!e.getKey().equals("columnsId")).limit(8).map(e->(value.kind.equals("map")?e.getKey().substring(e.getKey().lastIndexOf(':')+1):e.getKey())+": "+describe(e.getValue(),depth+1)).reduce((a,b)->a+"; "+b).orElse("Пусто");
        return s.length()>500?s.substring(0,500)+"…":s;
    }
    private static int[] position(String path){
        var match=java.util.regex.Pattern.compile("/rows/java.lang.Integer:(\\d+)(?:/cells/java.lang.Integer:(\\d+))?").matcher(path);
        return match.find()?new int[]{Integer.parseInt(match.group(1))+1,match.group(2)==null?0:Integer.parseInt(match.group(2))+1}:new int[]{0,0};
    }
    private static boolean structuralShift(SpreadsheetDocument a,SpreadsheetDocument b){
        // Unique content anchors detect coordinate movement; duplicate anchors are deliberately not guessed.
        if(ambiguousCoordinates(a,b,false)||ambiguousCoordinates(a,b,true))return true;
        var ar=anchors(a,false);var br=anchors(b,false);for(String key:ar.keySet())if(br.containsKey(key)&&!ar.get(key).equals(br.get(key)))return true;
        var ac=anchors(a,true);var bc=anchors(b,true);for(String key:ac.keySet())if(bc.containsKey(key)&&!ac.get(key).equals(bc.get(key)))return true;
        return false;
    }
    private static boolean ambiguousCoordinates(SpreadsheetDocument a,SpreadsheetDocument b,boolean columns){
        Set<Integer> ak=new TreeSet<>(),bk=new TreeSet<>();
        for(var row:a.getRows())if(columns)ak.addAll(row.getValue().getCells().keySet());else ak.add(row.getKey());
        for(var row:b.getRows())if(columns)bk.addAll(row.getValue().getCells().keySet());else bk.add(row.getKey());
        if(ak.equals(bk)||ak.isEmpty()||bk.isEmpty())return false;
        var av=coordinateTokens(a,columns);var bv=coordinateTokens(b,columns);
        var ac=tokenCoordinates(av);var bc=tokenCoordinates(bv);
        for(var entry:ac.entrySet())if(bc.containsKey(entry.getKey())&&(entry.getValue().size()>1||bc.get(entry.getKey()).size()>1)&&!entry.getValue().equals(bc.get(entry.getKey())))return true;
        var aa=anchors(a,columns);var ba=anchors(b,columns);return aa.keySet().stream().noneMatch(ba::containsKey);
    }
    private static Map<String,Set<Integer>> tokenCoordinates(Map<Integer,String> values){var out=new HashMap<String,Set<Integer>>();values.forEach((coordinate,text)->out.computeIfAbsent(text,k->new TreeSet<>()).add(coordinate));return out;}
    private static Map<Integer,String> coordinateTokens(SpreadsheetDocument doc,boolean columns){
        var values=new TreeMap<Integer,StringBuilder>();
        for(var row:new TreeMap<>(doc.getRows().map()).entrySet())for(var cell:new TreeMap<>(row.getValue().getCells().map()).entrySet()){
            var c=cell.getValue();String text=c.getParameter()!=null?atom(c.getParameter()).key():c.getText()==null||c.getText().getContent().isEmpty()?"":encode(c.getText(),doc,0,new int[1]).key();
            if(!text.isEmpty()){var value=values.computeIfAbsent(columns?cell.getKey():row.getKey(),i->new StringBuilder());Value.part(value,Integer.toString(columns?row.getKey():cell.getKey()));Value.part(value,text);}
        }
        var out=new TreeMap<Integer,String>();values.forEach((k,v)->out.put(k,v.toString()));return out;
    }
    private static Map<String,Integer> anchors(SpreadsheetDocument doc,boolean columns){
        var values=coordinateTokens(doc,columns);
        var result=new HashMap<String,Integer>();var duplicate=new HashSet<String>();values.forEach((k,v)->{if(result.put(v.toString(),k)!=null)duplicate.add(v.toString());});duplicate.forEach(result::remove);return result;
    }
}
