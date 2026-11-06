package com.codepilot1c.core.edt.metadata;
import java.util.*;
import org.eclipse.emf.ecore.*;

/** Effective values and EMF presence are reported separately; neither proves persisted XML. */
final class EdtCreationProperties {
    static Map<String,Object> inspect(EObject object,Set<String> fields) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(EStructuralFeature f:object.eClass().getEAllStructuralFeatures()) {
            if(f.isDerived() || f.isTransient() || f.isVolatile() || !fields.isEmpty() && !fields.contains(f.getName())){continue;}
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("effective",encode(object.eGet(f,false),2));
            value.put("model_is_set",object.eIsSet(f));
            value.put("emf_default",encode(f.getDefaultValue(),0));
            value.put("unsettable",f.isUnsettable());result.put(f.getName(),value);
        }
        return result;
    }
    private static Object encode(Object value,int depth) {
        if(value==null || value instanceof Boolean || value instanceof Number || value instanceof String){return value;}
        if(value instanceof Collection<?> values){return values.stream().limit(100).map(v->encode(v,depth)).toList();}
        if(value instanceof EObject o && o.eIsProxy()) {
            return Map.of("class",o.eClass().getName(),"proxy_uri",
                    ((org.eclipse.emf.ecore.InternalEObject)o).eProxyURI().toString());
        }
        if(value instanceof EObject o) {
            Map<String,Object> result=new LinkedHashMap<>();result.put("class",o.eClass().getName());
            if(depth>0) {
                for(EStructuralFeature f:o.eClass().getEAllStructuralFeatures()) {
                    if(!f.isDerived() && !f.isTransient() && !f.isVolatile() && (f instanceof EAttribute || f instanceof EReference r && r.isContainment())) {
                        result.put(f.getName(),encode(o.eGet(f,false),depth-1));
                    }
                }
            }
            return result;
        }
        return value.toString();
    }
}
