package com.codepilot1c.core.edt.metadata;
import java.util.*;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.*;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.*;

/** Real EDT reference cleanup; preparation must not run while holding a BM transaction. */
final class EdtNativeRefactoring {
    interface Work<T>{T run(IRefactoring refactoring);}
    static <T>T delete(MdObject target,Work<T> work) {
        BundleContext context=FrameworkUtil.getBundle(EdtNativeRefactoring.class).getBundleContext();
        ServiceReference<IMdRefactoringService> reference=context.getServiceReference(IMdRefactoringService.class);
        if(reference==null){throw unavailable("EDT metadata refactoring service is unavailable");}
        IMdRefactoringService service=context.getService(reference);
        try {
            if(service==null){throw unavailable("EDT metadata refactoring service stopped");}
            IRefactoring refactoring=service.createMdObjectDeleteRefactoring(List.of(target));
            if(refactoring==null){throw unavailable("EDT declined delete refactoring");}
            return work.run(refactoring);
        } finally {context.ungetService(reference);}
    }
    static <T>T rename(MdObject target,String newName,java.util.function.Function<Collection<IRefactoring>,T> work) {
        BundleContext context=FrameworkUtil.getBundle(EdtNativeRefactoring.class).getBundleContext();
        ServiceReference<IMdRefactoringService> reference=context.getServiceReference(IMdRefactoringService.class);
        if(reference==null){throw unavailable("EDT metadata refactoring service is unavailable");}
        try{IMdRefactoringService service=context.getService(reference);if(service==null){throw unavailable("EDT metadata refactoring service stopped");}
            Collection<IRefactoring> changes=service.createMdObjectRenameRefactoring(target,newName);
            if(changes==null || changes.isEmpty()){throw unavailable("EDT declined native rename refactoring");}
            return work.apply(changes);
        }finally{context.ungetService(reference);}
    }
    static Map<String,Object> preview(IRefactoring refactoring) {
        List<Map<String,Object>> problems=new ArrayList<>();boolean platformBlocked=false;
        for(IRefactoringProblem problem:refactoring.getStatus().getProblems()) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("kind",problem.getClass().getSimpleName());
            row.put("object",name(problem.getObject()));
            if(problem instanceof CleanReferenceProblem reference) {
                row.put("referencing_object",name(reference.getReferencingObject()));
                row.put("feature",reference.getReference()==null?null:reference.getReference().getName());
            } else {platformBlocked=true;}
            problems.add(row);
        }
        List<Map<String,Object>> items=new ArrayList<>();
        for(IRefactoringItem item:refactoring.getItems()){items.add(Map.of("name",String.valueOf(item.getName()),"optional",item.isOptional(),"checked",item.isChecked()));}
        Map<String,Object> result=new LinkedHashMap<>();result.put("strategy","edt-md-refactoring");result.put("title",refactoring.getTitle());
        result.put("items",items);result.put("problems",problems);result.put("blocked",!problems.isEmpty());result.put("platform_blocked",platformBlocked);return result;
    }
    static void perform(IRefactoring refactoring,boolean force) {
        Map<String,Object> impact=preview(refactoring);
        if(Boolean.TRUE.equals(impact.get("platform_blocked")) || !force && Boolean.TRUE.equals(impact.get("blocked"))) {
            throw new MetadataOperationException(MetadataOperationCode.METADATA_DELETE_CONFLICT,"EDT delete is blocked: "+new com.google.gson.Gson().toJson(impact),false);
        }
        try {refactoring.perform();} catch(RuntimeException error) {
            throw new MetadataOperationException(MetadataOperationCode.EDT_TRANSACTION_FAILED,"EDT refactoring failed; final state is unknown. Inspect metadata and disk before retrying.",false,error);
        }
    }
    private static String name(EObject object){return object instanceof MdObject md?md.eClass().getName()+"."+md.getName():object==null?"unknown":object.eClass().getName();}
    private static MetadataOperationException unavailable(String message){return new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,message,false);}
}
