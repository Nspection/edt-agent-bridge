// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import java.util.*;
import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.core.infobases.*;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;

/** Association-only access to existing EDT registrations. No filesystem path admission bypass. */
public final class RegisteredInfobaseGateway extends NativeInfobaseGateway implements InfobaseBindingService.Gateway {
    private final EdtRuntimeGateway access=new EdtRuntimeGateway();
    private InfobaseReference registered(String uuid){
        UUID id;try{id=UUID.fromString(uuid);}catch(Exception e){throw new IllegalArgumentException("Invalid base_uuid");}
        var ref=access.getInfobaseManager().findInfobaseByUuid(id).orElseThrow(()->new IllegalArgumentException("REGISTERED_BASE_NOT_FOUND"));
        if(ref.getConnectionString()==null)throw new IllegalStateException("Registered base has no target");return ref;
    }
    private static List<Object> contextKey(InfobaseAssociationContext context){
        var result=new ArrayList<Object>();var visited=Collections.newSetFromMap(new IdentityHashMap<InfobaseAssociationContext,Boolean>());
        for(var c=context;c!=null;c=c.getReference().orElse(null)){if(!visited.add(c)||visited.size()>32)throw new IllegalStateException("Invalid EDT context");result.add(List.of(c.getContext().isPresent(),c.getContext().orElse("")));}return result;
    }
    private static String identity(InfobaseReference ref){return InfobasePaths.hash(InfobasePaths.connectionIdentity(ref.getConnectionString().asConnectionString()));}
    private static String primary(IInfobaseAssociation a){return a==null||a.getDefaultInfobase()==null?"":a.getDefaultInfobase().getUuid().toString();}
    /** Operation identity only: no workspace-wide owner inventory and no synchronization/DB call. */
    public Map<String,Object> describeScope(String name,String uuid)throws Exception {
        IProject project=project(name);var ref=registered(uuid);var context=currentContext(project);
        var association=access.getInfobaseAssociationManager().getAssociation(project,context).orElse(null);
        return Map.of("project",name,"project_path",project.getLocation().toOSString(),
                "context",contextKey(context),"base_uuid",uuid,"target_identity_sha256",identity(ref),
                "primary_uuid",primary(association));
    }
    public Map<String,Object> describe(String name,String uuid)throws Exception{
        IProject p=project(name);var ref=registered(uuid);var context=currentContext(p);var manager=access.getInfobaseAssociationManager();var association=manager.getAssociation(p,context).orElse(null);
        var out=new LinkedHashMap<String,Object>();out.put("project",name);out.put("project_path",p.getLocation().toOSString());out.put("context",contextKey(context));out.put("base_uuid",uuid);out.put("base_name",Objects.toString(ref.getName(),""));out.put("target_identity_sha256",identity(ref));out.put("runtime_version",Objects.toString(ref.getVersion(),""));out.put("owners",ownerProjects(ref));out.put("primary_uuid",primary(association));out.put("associated",association!=null&&association.getInfobases().stream().anyMatch(x->ref.getUuid().equals(x.getUuid())));return out;
    }
    public List<Map<String,Object>> list(String filter,int offset,int limit)throws Exception{
        var resource=access.getInfobaseManager().getInfobasesResource();var rows=new TreeMap<String,Map<String,Object>>();
        if(resource!=null){var it=resource.getAllContents();int visited=0;while(it.hasNext()){
            var item=it.next();if(++visited>20000)throw new IllegalStateException("Registered-base inventory exceeds limit");
            if(item instanceof InfobaseReference ref&&ref.getUuid()!=null&&ref.getConnectionString()!=null){String name=Objects.toString(ref.getName(),"");if(!name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))continue;rows.put(ref.getUuid().toString(),Map.of("base_uuid",ref.getUuid().toString(),"name",name,"runtime_version",Objects.toString(ref.getVersion(),""),"owners",ownerProjects(ref)));}
        }}return new ArrayList<>(rows.values());
    }
    public Map<String,Object> bind(String name,String uuid,Map<String,Object> expected,boolean setPrimary)throws Exception{
        var p=project(name);var ref=registered(uuid);var context=currentContext(p);
        if(!describe(name,uuid).equals(expected)||!contextKey(context).equals(expected.get("context")))throw new IllegalStateException("STALE_BINDING_CONTEXT");
        var manager=access.getInfobaseAssociationManager();
        if(!Boolean.TRUE.equals(expected.get("associated")))manager.associate(p,ref,InfobaseAssociationSettings.notSynchronized(context));
        if(setPrimary&&!uuid.equals(expected.get("primary_uuid")))manager.setDefaultInfobase(p,ref,context);
        if(!currentContext(p).equals(context))throw new IllegalStateException("EDT_CONTEXT_CHANGED_DURING_BINDING");
        var actual=describe(name,uuid);
        if(!Objects.equals(actual.get("target_identity_sha256"),expected.get("target_identity_sha256"))||!Objects.equals(actual.get("context"),expected.get("context"))||!Boolean.TRUE.equals(actual.get("associated"))||(setPrimary&&!uuid.equals(actual.get("primary_uuid"))))throw new IllegalStateException("BINDING_VERIFICATION_FAILED");
        return actual;
    }
    public java.nio.file.Path bindingRoot(){return stateRoot().resolve("registered-bindings");}
}
