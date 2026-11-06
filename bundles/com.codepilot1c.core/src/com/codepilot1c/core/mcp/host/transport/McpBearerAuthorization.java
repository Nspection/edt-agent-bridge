// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host.transport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.sun.net.httpserver.Headers;
/** Local MCP token only. No accounts, login, OAuth registration or refresh tokens. */
public final class McpBearerAuthorization {
 private final byte[] token;
 public McpBearerAuthorization(String bindAddress,int port,String token){this.token=(token==null?"":token).getBytes(StandardCharsets.UTF_8);}
 public boolean isAuthorized(Headers headers){
  var values=headers.get("Authorization");
  if(token.length==0 || values==null || values.size()!=1)return false;
  String header=values.get(0);
  if(header==null || !header.regionMatches(true,0,"Bearer ",0,7))return false;
  return MessageDigest.isEqual(token,header.substring(7).getBytes(StandardCharsets.UTF_8));
 }
 public String buildWwwAuthenticateHeader(){return "Bearer realm=\"EDT Agent Bridge\"";}
}
