"""Read-only preservation check for the native-tools contribution (no EDT/base launch)."""
from pathlib import Path
import argparse,subprocess,xml.etree.ElementTree as ET,re
p=argparse.ArgumentParser();p.add_argument('--base',default='846b37eafa6fa0b41ca5b9c3729148114c10e384');a=p.parse_args();root=Path(__file__).resolve().parent.parent
def git(*args):return subprocess.check_output(['git',*args],cwd=root)
protected=['README.md','pom.xml','runtime','features','repositories','site','bundles/com.codepilot1c.core/pom.xml','bundles/com.codepilot1c.ui/pom.xml','bundles/com.codepilot1c.core/src/com/codepilot1c/core/provider','bundles/com.codepilot1c.core/src/com/codepilot1c/core/backend','bundles/com.codepilot1c.core/src/com/codepilot1c/core/agent','bundles/com.codepilot1c.core/src/com/codepilot1c/core/remote','bundles/com.codepilot1c.core/src/com/codepilot1c/core/memory','bundles/com.codepilot1c.core/src/com/codepilot1c/core/skills','bundles/com.codepilot1c.core/src/com/codepilot1c/core/ui','bundles/com.codepilot1c.core/src/com/codepilot1c/core/gsd','bundles/com.codepilot1c.core/src/com/codepilot1c/core/internal/VibeCorePlugin.java','bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/ToolRegistry.java','bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface','bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfig.java','bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostConfigStore.java','bundles/com.codepilot1c.core/src/com/codepilot1c/core/mcp/host/McpHostServer.java','bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/views','bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/preferences','bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/internal/VibeUiPlugin.java','bundles/com.codepilot1c.ui/src/com/codepilot1c/ui/startup/McpHostStartup.java']
for item in protected:assert not git('diff','--name-only',a.base,'--',item).strip(),'Protected upstream content changed: '+item
assert not git('diff','--name-only','--diff-filter=D',a.base).strip(),'Upstream files deleted'
for bundle in ['core','ui']:
 path=f'bundles/com.codepilot1c.{bundle}/plugin.xml';old=ET.fromstring(git('show',a.base+':'+path));new=ET.parse(root/path).getroot()
 def signature(node):return (node.tag,tuple(sorted(node.attrib.items())),(node.text or '').strip(),tuple(signature(child) for child in node))
 original=[signature(x) for x in old];current=[signature(x) for x in new]
 for item in original:assert item in current,'Original plugin contribution changed in '+path
 path=f'bundles/com.codepilot1c.{bundle}/META-INF/MANIFEST.MF'
 def headers(text):
  result={};key=None
  for line in text.splitlines():
   if line.startswith(' ') and key:result[key]+=' '+line.strip()
   elif ': ' in line:key,value=line.split(': ',1);result[key]=value
  return result
 old=headers(git('show',a.base+':'+path).decode());new=headers((root/path).read_text())
 for key,value in old.items():
  if key not in ['Import-Package','Export-Package']:assert new.get(key)==value,(path,key)
  else:
   split=lambda x:{t.strip() for t in re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)',x)}
   assert split(value)<=split(new[key]),(path,key,'original package requirement removed/changed')
print('PASS: upstream chat/provider/account/GSD/registry/build content retained; no deletions; original plugin/manifest contracts retained')
