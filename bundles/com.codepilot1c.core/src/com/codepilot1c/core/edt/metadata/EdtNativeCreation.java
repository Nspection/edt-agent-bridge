package com.codepilot1c.core.edt.metadata;

import java.util.Objects;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;

/** Project-aware EDT initialization. Never silently substitutes a bare EMF object. */
final class EdtNativeCreation {
    private final EdtMetadataGateway gateway;
    private final Supplier<IModelObjectFactory> factories;
    private final ThreadLocal<IProject> project = new ThreadLocal<>();
    EdtNativeCreation(EdtMetadataGateway gateway) { this(gateway,EdtNativeCreation::platformFactory); }
    EdtNativeCreation(EdtMetadataGateway gateway,Supplier<IModelObjectFactory> factories) { this.gateway=gateway;this.factories=factories; }
    <T> T inProject(IProject current, Supplier<T> work) {
        IProject previous=project.get();project.set(Objects.requireNonNull(current));
        try {return work.get();} finally {if(previous==null){project.remove();}else{project.set(previous);}}
    }
    <T extends EObject> T create(EClass type, EObject owner) {
        IProject current=project.get();
        if(current==null){throw unavailable("Creation requires an EDT project transaction",null);}
        IV8Project v8=gateway.getV8ProjectManager().getProject(current);
        if(v8==null){throw unavailable("EDT project context is not ready",null);}
        IModelObjectFactory factory=factories.get();
        T created=owner==null?factory.create(type,v8):factory.create(type,owner,v8.getVersion());
        if(created==null){throw unavailable("EDT factory declined "+type.getName()+"; no bare object was created",null);}
        return created;
    }
    <T extends EObject> T createForm(EClass type,EObject owner) {
        IProject current=project.get();
        if(current==null){throw unavailable("Form creation requires an EDT project transaction",null);}
        IV8Project v8=gateway.getV8ProjectManager().getProject(current);
        if(v8==null){throw unavailable("EDT form project context is not ready",null);}
        Bundle bundle=Platform.getBundle("com._1c.g5.v8.dt.form");
        if(bundle==null){throw unavailable("EDT form bundle is unavailable",null);}
        try {
            if(bundle.getState()!=Bundle.ACTIVE){bundle.start(Bundle.START_TRANSIENT);}
            var context=bundle.getBundleContext();
            var references=context.getServiceReferences(IModelObjectFactory.class,"(service.name=FormModelObjectFactory)");
            if(references.isEmpty()){throw unavailable("EDT FormModelObjectFactory is unavailable",null);}
            var reference=references.iterator().next();IModelObjectFactory factory=context.getService(reference);
            try {
                if(factory==null){throw unavailable("EDT form factory stopped",null);}
                T created=factory.create(type,owner,v8.getVersion());
                if(created==null){throw unavailable("EDT form factory declined "+type.getName(),null);}
                return created;
            } finally {context.ungetService(reference);}
        } catch(org.osgi.framework.BundleException | org.osgi.framework.InvalidSyntaxException error){throw unavailable("Cannot resolve EDT form initialization",error);}
    }
    void fillReferences(EObject object) { factories.get().fillDefaultReferences(object); }
    static IModelObjectFactory platformFactory() {
        try {
            Bundle bundle=Platform.getBundle("com._1c.g5.v8.dt.md");
            if(bundle==null){throw new IllegalStateException("EDT metadata bundle is unavailable");}
            if(bundle.getState()!=Bundle.ACTIVE){bundle.start(Bundle.START_TRANSIENT);}
            Class<?> plugin=bundle.loadClass("com._1c.g5.v8.dt.md.MdPlugin");
            Object instance=plugin.getMethod("getDefault").invoke(null);
            Object injector=plugin.getMethod("getInjector").invoke(instance);
            Class<?> injectorType=bundle.loadClass("com.google.inject.Injector");
            return (IModelObjectFactory)injectorType.getMethod("getInstance",Class.class).invoke(injector,IModelObjectFactory.class);
        } catch(Exception error){Throwable cause=error;while(cause.getCause()!=null){cause=cause.getCause();}
            throw unavailable("EDT metadata initialization factory is unavailable: "+cause.getClass().getSimpleName()+": "+cause.getMessage(),error);}
    }
    private static MetadataOperationException unavailable(String message,Throwable cause) {
        return new MetadataOperationException(MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,message,false,cause);
    }
}
