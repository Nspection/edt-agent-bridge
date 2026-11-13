// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;

/** Bounded persistent form-model fingerprint; never exports model values to diagnostics. */
final class FormProjectionFingerprint {
    private final MessageDigest digest;private int nodes,bytes;
    private FormProjectionFingerprint()throws Exception{digest=MessageDigest.getInstance("SHA-256");}
    static String of(EObject form)throws Exception {
        var fingerprint=new FormProjectionFingerprint();fingerprint.node(form);
        return HexFormat.of().formatHex(fingerprint.digest.digest());
    }
    private void text(String value){
        byte[] data=value.getBytes(StandardCharsets.UTF_8);bytes+=data.length;
        if(bytes>2*1024*1024)throw new IllegalStateException("Form fingerprint exceeds 2MiB");
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);
    }
    private void node(EObject object){
        if(++nodes>10000)throw new IllegalStateException("Form fingerprint exceeds 10000 nodes");
        text(object.eClass().getEPackage().getNsURI());text(object.eClass().getName());
        var features=new ArrayList<>(object.eClass().getEAllStructuralFeatures());features.sort(Comparator.comparing(EStructuralFeature::getName));
        for(var feature:features){
            if(feature.isDerived()||feature.isTransient())continue;
            text(feature.getName());text(Boolean.toString(object.eIsSet(feature)));
            Object value=object.eGet(feature,false);
            if(value==null){text("null");continue;}
            List<?> values=feature.isMany()?(List<?>)value:Collections.singletonList(value);text(Integer.toString(values.size()));
            for(Object item:values){
                if(feature instanceof EAttribute attribute)text(EcoreUtil.convertToString(attribute.getEAttributeType(),item));
                else if(feature instanceof EReference reference&&reference.isContainment())node((EObject)item);
                else if(item instanceof EObject target)text(EcoreUtil.getURI(target).toString());
                else throw new IllegalStateException("Unsupported persistent form value");
            }
        }
    }
}
