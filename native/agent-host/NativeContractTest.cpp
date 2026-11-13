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
LRESULT CALLBACK paintProbe(HWND w,UINT message,WPARAM arg,LPARAM data){
 if(message==WM_PRINT||message==WM_PRINTCLIENT){HDC dc=(HDC)arg;RECT r;GetClientRect(w,&r);int bands=GetWindowLongPtr(w,GWLP_USERDATA)==1?3:1;COLORREF colors[]={RGB(220,20,30),RGB(20,210,30),RGB(20,30,220)};for(int i=0;i<bands;i++){RECT row{0,r.bottom*i/bands,r.right,r.bottom*(i+1)/bands};HBRUSH brush=CreateSolidBrush(bands==1?RGB(240,240,240):colors[i]);FillRect(dc,&row,brush);DeleteObject(brush);}return 0;}return DefWindowProc(w,message,arg,data);
}
int desktopProbe(int argc,char** argv){
 // This path must never create windows on an input/user desktop.
 wchar_t name[256]{};DWORD bytes=0;assert(GetUserObjectInformationW(GetThreadDesktop(GetCurrentThreadId()),UOI_NAME,name,sizeof(name),&bytes));assert(wcsncmp(name,L"EDTBridge-",10)==0);
 HMODULE library=LoadLibraryW(wide(argv[2]).c_str());assert(library);
 auto create=(long(*)(const WCHAR_T*,IComponentBase**))GetProcAddress(library,"GetClassObject");auto destroy=(long(*)(IComponentBase**))GetProcAddress(library,"DestroyObject");assert(create&&destroy);
 IComponentBase* component=nullptr;assert(create(L"AgentHost",&component));Memory memory;assert(component->setMemManager(&memory));
 auto call=[&](const wchar_t* method,const std::vector<std::wstring>& args){std::vector<tVariant> params(args.size());for(size_t i=0;i<args.size();i++){params[i].vt=VTYPE_PWSTR;params[i].pwstrVal=(WCHAR_T*)args[i].data();params[i].wstrLen=(uint32_t)args[i].size();}tVariant result{};assert(component->CallAsFunc(component->FindMethod(method),&result,params.data(),(long)params.size()));std::wstring text(result.pwstrVal,result.wstrLen);void* ptr=result.pwstrVal;memory.FreeMemory(&ptr);return text;};
 call(L"Configure",{wide(argv[3]),std::wstring(64,L'0')});
 WNDCLASSW cls{};cls.lpfnWndProc=paintProbe;cls.hInstance=GetModuleHandle(nullptr);cls.lpszClassName=L"BridgeOwnedPopupProbe";assert(RegisterClassW(&cls));
 HWND root=CreateWindowW(cls.lpszClassName,L"Owned root",WS_OVERLAPPEDWINDOW,10,10,320,220,nullptr,nullptr,cls.hInstance,nullptr);assert(root);ShowWindow(root,SW_SHOWNOACTIVATE);
 call(L"ArmScreenshot",{L"popup"});
 HWND card=CreateWindowW(cls.lpszClassName,L"Owned card",WS_OVERLAPPEDWINDOW,30,30,200,120,root,nullptr,cls.hInstance,nullptr);assert(card);ShowWindow(card,SW_SHOWNOACTIVATE);assert(call(L"CaptureState",{}).find(L"\"capture_ready\":false")!=std::wstring::npos);
 HWND popup=CreateWindowW(cls.lpszClassName,L"",WS_POPUP,270,180,180,120,root,nullptr,cls.hInstance,nullptr);assert(popup);SetWindowLongPtr(popup,GWLP_USERDATA,1);ShowWindow(popup,SW_SHOWNOACTIVATE);
 assert(call(L"CaptureState",{}).find(L"\"capture_ready\":true")!=std::wstring::npos);
 DestroyWindow(popup);Sleep(1100);call(L"TakeArmedScreenshot",{});
 assert(call(L"CaptureState",{}).find(L"\"capture_expired\":true")!=std::wstring::npos);
 call(L"Screenshot",{});call(L"ArmScreenshot",{L"popup"});call(L"CancelScreenshot",{});assert(call(L"CaptureState",{}).find(L"\"capture_expired\":true")!=std::wstring::npos);
 DestroyWindow(card);DestroyWindow(root);component->Done();assert(!destroy(&component));assert(memory.owned.empty());FreeLibrary(library);return 0;
}
int main(int argc,char** argv){
 if(argc>1){assert(argc==4&&std::string(argv[1])=="--desktop-probe");return desktopProbe(argc,argv);}

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
