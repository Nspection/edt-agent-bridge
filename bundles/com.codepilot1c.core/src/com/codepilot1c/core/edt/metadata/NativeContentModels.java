package com.codepilot1c.core.edt.metadata;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.emf.common.util.URI;
import com._1c.g5.v8.dt.moxel.*;
/** Stable model fingerprints and bounded content projections, not raw XML mutation. */
final class NativeContentModels {
    static SpreadsheetDocument createSheet(org.eclipse.core.resources.IProject project) {
        var context=org.osgi.framework.FrameworkUtil.getBundle(NativeContentModels.class).getBundleContext();
        var reference=context.getServiceReference(com._1c.g5.v8.dt.core.platform.IDtProjectManager.class);
        if(reference==null){throw new IllegalStateException("EDT project manager is unavailable");}
        try{var manager=context.getService(reference);if(manager==null){throw new IllegalStateException("EDT project manager stopped");}var nativeProject=manager.getDtProject(project);if(nativeProject==null){throw new IllegalStateException("EDT project model is not ready");}return com._1c.g5.v8.dt.moxel.sheet.SheetFactory.createInteractiveSpreadsheetDocument(nativeProject);}finally{context.ungetService(reference);}
    }
    static String fingerprint(EObject object){
        if(object==null){return hash("null");}
        try{var resource=new XMIResourceImpl(URI.createURI("memory:/content.xmi"));var copier=copyGraph(object);
            for(var copy:copier.values()){if(copy.eContainer()==null){resource.getContents().add(copy);}}
            var output=new ByteArrayOutputStream();resource.save(output,Map.of("ENCODING","UTF-8"));if(output.size()>16*1024*1024){throw new IllegalArgumentException("Content exceeds 16 MB snapshot limit");}return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));}catch(IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Cannot fingerprint native content",e);}
    }
    private static EcoreUtil.Copier copyGraph(EObject object){
        var copier=new EcoreUtil.Copier();
            var queue=new ArrayDeque<EObject>();var seen=Collections.newSetFromMap(new IdentityHashMap<EObject,Boolean>());queue.add(object);
            while(!queue.isEmpty()){
                var current=queue.removeFirst();if(!seen.add(current)){continue;}
                if(seen.size()>100000){throw new IllegalArgumentException("Native content graph exceeds snapshot limit");}
                copier.copy(current);
                for(var reference:current.eClass().getEAllReferences()){
                    if(reference.isDerived() || reference.isTransient() || reference.isContainer()){continue;}
                    var value=current.eGet(reference,false);
                    if(value instanceof EObject target){queue.add(target);}
                    else if(value instanceof Iterable<?> values){for(var item:values){if(item instanceof EObject target){queue.add(target);}}}
                }
            }
        copier.copyReferences();return copier;
    }
    static <T extends EObject> T detachedCopy(T object){
        @SuppressWarnings("unchecked") T copy=(T)copyGraph(object).get(object);return copy;
    }
    static com.google.gson.JsonObject sheetPatch(Map<String,Object> patch){
        var spec=new com.google.gson.Gson().toJsonTree(patch).getAsJsonObject();
        for(String array:List.of("cells","merges","areas","columnWidths","rowHeights")){
            if(!spec.has(array)){continue;}
            if(!spec.get(array).isJsonArray()){throw new IllegalArgumentException(array+" must be an array");}
            for(var item:spec.getAsJsonArray(array)){
                if(!item.isJsonObject()){throw new IllegalArgumentException(array+" entries must be objects");}
                var entry=item.getAsJsonObject();
                for(String key:List.of("row","col","fromRow","fromCol","toRow","toCol")){
                    if(!entry.has(key)){continue;}
                    try{var value=entry.get(key).getAsBigDecimal();int index=value.intValueExact();if(index<1){throw new ArithmeticException();}entry.addProperty(key,index-1);}
                    catch(RuntimeException e){throw new IllegalArgumentException(array+"."+key+" requires a positive 1-based integer");}
                }
            }
        }
        return spec;
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static List<Map<String,Object>> predefined(EObject owner){
        List<Map<String,Object>> result=new ArrayList<>();var gson=new com.google.gson.Gson();
        for(var row:PredefinedWriter.listAll(owner)){
            @SuppressWarnings("unchecked") Map<String,Object> item=gson.fromJson(gson.toJson(row),Map.class);
            var nativeItem=PredefinedWriter.findByNameExact(owner,row.name);item.put("id",String.valueOf(nativeItem.getId()));result.add(item);
        }return result;
    }
    static Map<String,Object> sheet(SpreadsheetDocument document,int fromRow,int toRow,int fromCol,int toCol,int limit){
        if(fromRow<1 || fromCol<1 || toRow<fromRow || toCol<fromCol || limit<1 || limit>500){throw new IllegalArgumentException("Invalid 1-based cell range or limit (1..500)");}
        if(document==null){return Map.of("initialized",false,"cells",List.of(),"state_sha256",fingerprint(null));}
        List<Map<String,Object>> cells=new ArrayList<>();boolean more=false;
        outer:for(var row:document.getRows()){
            int r=row.getKey()+1;if(r<fromRow || r>toRow){continue;}
            for(var entry:row.getValue().getCells()){
                int c=entry.getKey()+1;if(c<fromCol || c>toCol){continue;}if(cells.size()==limit){more=true;break outer;}
                var cell=entry.getValue();Map<String,Object> value=new LinkedHashMap<>();value.put("row",r);value.put("col",c);value.put("format_index",cell.getFormatIndex());
                if(cell.getParameter()!=null){value.put("parameter",cell.getParameter());}if(cell.getText()!=null){value.put("text_by_language",new LinkedHashMap<>(cell.getText().getContent().map()));}cells.add(value);
            }
        }
        List<Map<String,Object>> areas=new ArrayList<>();
        for(var entry:document.getNamedItems()){if(entry.getValue() instanceof NamedItemCells named && named.getArea() instanceof RectArea area){var rect=area.getPosition();if(rect!=null){areas.add(Map.of("name",entry.getKey(),"from_row",rect.getY()+1,"from_col",rect.getX()+1,"to_row",rect.getY()+rect.getHeight()+1,"to_col",rect.getX()+rect.getWidth()+1));}}}
        return Map.of("initialized",true,"cells",cells,"has_more",more,"named_areas",areas,"merge_count",document.getMerges().size(),"format_count",document.getFormats().size(),"state_sha256",fingerprint(document));
    }
}
