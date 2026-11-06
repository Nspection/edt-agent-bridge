// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
/** Structured endpoint fields, never a free-form command line or a filesystem path. */
public record ServerInfobaseTarget(String address,String name) {
    public ServerInfobaseTarget {
        if(address==null || !address.matches("[A-Za-z0-9_.-]+(?::[0-9]{1,5})?")) throw new IllegalArgumentException("server_address must be a host with optional cluster port");
        if(address.contains(":")) {int port=Integer.parseInt(address.substring(address.lastIndexOf(':')+1));if(port<1 || port>65535)throw new IllegalArgumentException("Invalid server cluster port");}
        if(name==null || name.isBlank() || name.length()>128 || name.chars().anyMatch(c->c<32 || c=='\"' || c==';' || c=='\\' || c=='/')) throw new IllegalArgumentException("Invalid server_infobase name");
    }
    public InfobaseReference reference(){return InfobaseReferences.newServerInfobaseReference(address,name);}
    public String connection(){return reference().getConnectionString().asConnectionString();}
}
