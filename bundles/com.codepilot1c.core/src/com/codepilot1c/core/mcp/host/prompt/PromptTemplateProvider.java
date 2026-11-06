package com.codepilot1c.core.mcp.host.prompt;
import java.util.*;
import com.codepilot1c.core.mcp.model.*;
/** MCP workflow guidance; no embedded agent, prompt registry or LLM. */
public final class PromptTemplateProvider implements IMcpPromptProvider {
 public List<McpPrompt> listPrompts(){ return List.of(new McpPrompt("edt-development", "Discover EDT tools and run a native development/test cycle")); }
 public Optional<McpPromptResult> getPrompt(String name,Map<String,Object> arguments){
  if(!"edt-development".equals(name))return Optional.empty();
  var result=new McpPromptResult();result.setDescription("EDT Agent Bridge development workflow");
  var message=new McpPromptResult.PromptMessage();message.setRole("user");
  message.setContent(McpContent.text("Start with edt_capabilities and inspect the project. Use native EDT metadata/form tools for creation and defaults. Read edt_runner_profile before starting a development run; poll status, inspect suspended threads explicitly, review screenshots, and continue using fresh checkpoint identifiers. Clean up only resources owned by your run."));
  result.setMessages(List.of(message));return Optional.of(result);
 }
}
