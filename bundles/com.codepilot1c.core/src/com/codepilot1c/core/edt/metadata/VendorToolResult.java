package com.codepilot1c.core.edt.metadata;
/** Error adapter for vendored EDT-MCP content helpers; no tool execution or transport. */
final class VendorToolResult {
    private final String message;
    private VendorToolResult(String message){this.message=message;}
    static String cp(int... points){return new String(points,0,points.length);}
    static VendorToolResult error(String message){return new VendorToolResult(message);}
    String toJson(){return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(java.util.Map.of("isError",true,"content",java.util.List.of(java.util.Map.of("type","text","text",message))));}
}
