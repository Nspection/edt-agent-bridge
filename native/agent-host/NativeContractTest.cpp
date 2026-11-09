// SPDX-License-Identifier: AGPL-3.0-only
#include <cstdlib>
#include <set>
#include <cassert>
#include "AgentHost.cpp"
class Memory final : public IMemoryManager {
public:std::set<void*> owned;
 bool ADDIN_API AllocMemory(void** out,unsigned long n)override{*out=malloc(n);owned.insert(*out);return *out!=nullptr;}
 void ADDIN_API FreeMemory(void** out)override{assert(owned.erase(*out)==1);free(*out);*out=nullptr;}
};
int main(){
 IComponentBase* component=nullptr;assert(GetClassObject(L"AgentHost",&component));Memory memory;assert(component->setMemManager(&memory));
 WCHAR_T* extension=nullptr;assert(component->RegisterExtensionAs(&extension));void* p=extension;memory.FreeMemory(&p);
 for(long i=0;i<component->GetNMethods();i++){
  for(long alias=0;alias<2;alias++){
   auto name=component->GetMethodName(i,alias);assert(name);assert(component->FindMethod(name)==i);p=(void*)name;memory.FreeMemory(&p);
  }
  assert(!component->GetMethodName(i,2));
 }
 tVariant capabilities{};assert(component->CallAsFunc(component->FindMethod(L"Capabilities"),&capabilities,nullptr,0));assert(capabilities.vt==VTYPE_PWSTR);assert(wcsstr((wchar_t*)capabilities.pwstrVal,L"screenshot_png"));p=capabilities.pwstrVal;memory.FreeMemory(&p);
 assert(memory.owned.empty());assert(!DestroyObject(&component));assert(!component);
 return 0;
}
