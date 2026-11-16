// SPDX-License-Identifier: AGPL-3.0-only
#include <cstdlib>
#include <set>
#include <cassert>
#include <fstream>
#include "AgentHost.cpp"
#include <winsock2.h>
#pragma comment(lib, "ws2_32.lib")
class Memory final : public IMemoryManager {
public:std::set<void*> owned;
 bool ADDIN_API AllocMemory(void** out,unsigned long n)override{*out=malloc(n);owned.insert(*out);return *out!=nullptr;}
 void ADDIN_API FreeMemory(void** out)override{assert(owned.erase(*out)==1);free(*out);*out=nullptr;}
};
HDC sourceLayer=nullptr;
LRESULT CALLBACK paintProbe(HWND w,UINT message,WPARAM arg,LPARAM data){
 if(message==WM_PRINT||message==WM_PRINTCLIENT){if(GetWindowLongPtr(w,GWLP_USERDATA)==2)return 0;if(GetWindowLongPtr(w,GWLP_USERDATA)==3){assert(sourceLayer);BLENDFUNCTION blend{AC_SRC_OVER,0,255,AC_SRC_ALPHA};assert(AlphaBlend((HDC)arg,0,0,40,40,sourceLayer,0,0,40,40,blend));return 0;}HDC dc=(HDC)arg;RECT r;GetClientRect(w,&r);int bands=GetWindowLongPtr(w,GWLP_USERDATA)==1?3:1;COLORREF colors[]={RGB(220,20,30),RGB(20,210,30),RGB(20,30,220)};for(int i=0;i<bands;i++){RECT row{0,r.bottom*i/bands,r.right,r.bottom*(i+1)/bands};HBRUSH brush=CreateSolidBrush(bands==1?RGB(240,240,240):colors[i]);FillRect(dc,&row,brush);DeleteObject(brush);}return 0;}return DefWindowProc(w,message,arg,data);
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
 int cornerX=0,cornerY=0,alphaX=0,alphaY=0;
 auto location=[&](HWND window,int& x,int& y){auto list=ownSurfaces();RECT bounds=list.windows[0].frame;for(auto& item:list.windows){RECT next;UnionRect(&next,&bounds,&item.frame);bounds=next;}auto target=surface(window);x=target.rect.left-bounds.left;y=target.rect.top-bounds.top;};
 call(L"ArmScreenshot",{L"popup"});
 HWND card=CreateWindowW(cls.lpszClassName,L"Owned card",WS_OVERLAPPEDWINDOW,30,30,200,120,root,nullptr,cls.hInstance,nullptr);assert(card);ShowWindow(card,SW_SHOWNOACTIVATE);assert(call(L"CaptureState",{}).find(L"\"capture_ready\":false")!=std::wstring::npos);
 CaptureGate transientGate;
 HWND transient=CreateWindowW(cls.lpszClassName,L"",WS_POPUP,40,40,8,8,root,nullptr,cls.hInstance,nullptr);assert(transient);ShowWindow(transient,SW_SHOWNOACTIVATE);DestroyWindow(transient);
 HWND popup=CreateWindowW(cls.lpszClassName,L"",WS_POPUP,270,180,180,120,root,nullptr,cls.hInstance,nullptr);assert(popup);SetWindowLongPtr(popup,GWLP_USERDATA,1);assert(SetWindowRgn(popup,CreateRoundRectRgn(0,0,180,120,20,20),FALSE));ShowWindow(popup,SW_SHOWNOACTIVATE);
 transientGate.release();
 ULONGLONG captureEnd=GetTickCount64()+4000;bool ready=false;
 while(GetTickCount64()<captureEnd){MSG message;while(PeekMessage(&message,nullptr,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessage(&message);}if(call(L"CaptureState",{}).find(L"\"capture_ready\":true")!=std::wstring::npos){ready=true;break;}Sleep(10);}assert(ready);
 location(popup,cornerX,cornerY);DestroyWindow(popup);Sleep(1100);call(L"TakeArmedScreenshot",{});
 assert(call(L"CaptureState",{}).find(L"\"capture_expired\":true")!=std::wstring::npos);
 call(L"Screenshot",{});call(L"ArmScreenshot",{L"popup"});call(L"CancelScreenshot",{});assert(call(L"CaptureState",{}).find(L"\"capture_expired\":true")!=std::wstring::npos);
 {
  HWND layer=CreateWindowExW(WS_EX_LAYERED,cls.lpszClassName,L"",WS_POPUP,60,60,40,40,root,nullptr,cls.hInstance,nullptr);assert(layer);SetWindowLongPtr(layer,GWLP_USERDATA,2);
  HDC own=GetDC(root),dc=CreateCompatibleDC(own);BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=40;info.bmiHeader.biHeight=-40;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;void* pixels=nullptr;HBITMAP image=CreateDIBSection(own,&info,DIB_RGB_COLORS,&pixels,nullptr,0);assert(image&&pixels);
  {Selected selected(dc,image);for(int y=0;y<40;y++)for(int x=0;x<40;x++)((DWORD*)pixels)[y*40+x]=x<13?0:x<27?0x80800000:0xff141edc;POINT dest{60,60},origin{0,0};SIZE size{40,40};BLENDFUNCTION blend{AC_SRC_OVER,0,255,AC_SRC_ALPHA};assert(UpdateLayeredWindow(layer,own,&dest,&size,dc,&origin,0,&blend,ULW_ALPHA));ShowWindow(layer,SW_SHOWNOACTIVATE);location(layer,alphaX,alphaY);tVariant unavailable{};assert(!component->CallAsFunc(component->FindMethod(L"Screenshot"),&unavailable,nullptr,0));sourceLayer=dc;SetWindowLongPtr(layer,GWLP_USERDATA,3);call(L"Screenshot",{});std::fill_n((DWORD*)pixels,1600,0u);assert(UpdateLayeredWindow(layer,own,&dest,&size,dc,&origin,0,&blend,ULW_ALPHA));tVariant result{};assert(!component->CallAsFunc(component->FindMethod(L"Screenshot"),&result,nullptr,0));assert(memory.owned.empty());DestroyWindow(layer);sourceLayer=nullptr;}
  DeleteObject(image);DeleteDC(dc);ReleaseDC(root,own);
 }
 CaptureGate drainGate;auto begin=call(L"BeginScreenshot",{});std::wstring needle=L"\"capture_id\":\"";size_t start=begin.find(needle);assert(start!=std::wstring::npos);start+=needle.size();auto captureId=begin.substr(start,begin.find(L'"',start)-start);assert(call(L"PollScreenshot",{captureId}).find(L"capture_pending")!=std::wstring::npos);drainGate.release();captureEnd=GetTickCount64()+4000;bool asyncComplete=false;
 while(GetTickCount64()<captureEnd){MSG message;while(PeekMessage(&message,nullptr,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessage(&message);}auto response=call(L"PollScreenshot",{captureId});if(response.find(L"snapshot_id")!=std::wstring::npos){asyncComplete=true;break;}Sleep(10);}assert(asyncComplete);
 std::ofstream(argv[4])<<"{\"corner_x\":"<<cornerX<<",\"corner_y\":"<<cornerY<<",\"alpha_x\":"<<alphaX<<",\"alpha_y\":"<<alphaY<<"}";
 DestroyWindow(card);DestroyWindow(root);component->Done();assert(!destroy(&component));assert(memory.owned.empty());FreeLibrary(library);return 0;
}
// Each mode runs in a fresh process. Only the window thread is deliberately blocked.
struct BlockedWindow {
 HANDLE visible=CreateEventW(nullptr,TRUE,FALSE,nullptr);
 HANDLE entered=CreateEventW(nullptr,TRUE,FALSE,nullptr);
 HANDLE resume=CreateEventW(nullptr,TRUE,FALSE,nullptr);
 std::atomic<unsigned> prints{0};
 HWND window=nullptr;
 static LRESULT CALLBACK procedure(HWND w,UINT message,WPARAM arg,LPARAM data) {
  if(message==WM_NCCREATE){
   auto self=reinterpret_cast<CREATESTRUCTW*>(data)->lpCreateParams;
   SetWindowLongPtrW(w,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));
  }
  if(message==WM_PRINT || message==WM_PRINTCLIENT){
   auto self=reinterpret_cast<BlockedWindow*>(GetWindowLongPtrW(w,GWLP_USERDATA));
   ++self->prints;
   SetEvent(self->entered);
   assert(WaitForSingleObject(self->resume,12000)==WAIT_OBJECT_0);
   RECT bounds;GetClientRect(w,&bounds);FillRect(reinterpret_cast<HDC>(arg),&bounds,(HBRUSH)GetStockObject(WHITE_BRUSH));
   return 0;
  }
  if(message==WM_DESTROY){PostQuitMessage(0);return 0;}
  return DefWindowProcW(w,message,arg,data);
 }
 void run(HDESK desktop){
  assert(SetThreadDesktop(desktop));
  WNDCLASSW cls{};cls.lpfnWndProc=procedure;cls.hInstance=GetModuleHandle(nullptr);cls.lpszClassName=L"BridgeLifecycleProbe";
  assert(RegisterClassW(&cls));
  window=CreateWindowW(cls.lpszClassName,L"Owned print blocker",WS_OVERLAPPEDWINDOW,10,10,200,140,nullptr,nullptr,cls.hInstance,this);
  assert(window);ShowWindow(window,SW_SHOWNOACTIVATE);SetEvent(visible);
  MSG msg;while(GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}
 }
 ~BlockedWindow(){CloseHandle(visible);CloseHandle(entered);CloseHandle(resume);}
};

// A stale upload is observable even if WinHTTP eventually returns an error.
// Both sockets bind only ephemeral loopback ports and never contain real credentials.
struct BindingProbe {
 SOCKET listener=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
 std::wstring port;
 BindingProbe(){
  assert(listener!=INVALID_SOCKET);
  sockaddr_in address{};address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
  assert(bind(listener,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0);
  int size=sizeof(address);assert(getsockname(listener,reinterpret_cast<sockaddr*>(&address),&size)==0);
  assert(listen(listener,4)==0);port=std::to_wstring(ntohs(address.sin_port));
 }
 bool contacted(){
  fd_set readers;FD_ZERO(&readers);FD_SET(listener,&readers);timeval now{};
  int ready=select(0,&readers,nullptr,nullptr,&now);assert(ready>=0);return ready!=0;
 }
 ~BindingProbe(){closesocket(listener);}
};

struct TestComponent {
 IComponentBase* value=nullptr;
 Memory memory;
 using Create=long(*)(const WCHAR_T*,IComponentBase**);
 using Destroy=long(*)(IComponentBase**);
 Destroy destroy;
 explicit TestComponent(HMODULE dll):destroy(reinterpret_cast<Destroy>(GetProcAddress(dll,"DestroyObject"))){
  auto create=reinterpret_cast<Create>(GetProcAddress(dll,"GetClassObject"));
  assert(create&&destroy&&create(L"AgentHost",&value));assert(value->setMemManager(&memory));
 }
 bool invoke(const wchar_t* method,std::vector<std::wstring> args={},std::wstring* text=nullptr){
  std::vector<tVariant> params(args.size());
  for(size_t i=0;i<args.size();++i){params[i].vt=VTYPE_PWSTR;params[i].pwstrVal=args[i].data();params[i].wstrLen=(uint32_t)args[i].size();}
  tVariant result{};
  if(!value->CallAsFunc(value->FindMethod(method),&result,params.data(),(long)params.size()))return false;
  assert(result.vt==VTYPE_PWSTR);if(text)text->assign(result.pwstrVal,result.wstrLen);
  void* ptr=result.pwstrVal;memory.FreeMemory(&ptr);return true;
 }
 std::wstring begin(){
  std::wstring result;assert(invoke(L"BeginScreenshot",{},&result));
  std::wstring key=L"\"capture_id\":\"";auto start=result.find(key);assert(start!=std::wstring::npos);
  start+=key.size();auto end=result.find(L'"',start);assert(end!=std::wstring::npos&&end>start);
  return result.substr(start,end-start);
 }
 ~TestComponent(){value->Done();assert(!destroy(&value));assert(memory.owned.empty());}
};

int lifecycleProbe(const char* mode,const char* path){
 HDESK desktop=GetThreadDesktop(GetCurrentThreadId());wchar_t name[256]{};DWORD bytes=0;
 assert(GetUserObjectInformationW(desktop,UOI_NAME,name,sizeof(name),&bytes)&&wcsncmp(name,L"EDTBridge-",10)==0);
 HMODULE dll=LoadLibraryW(wide(path).c_str());assert(dll);
 WSADATA winsock{};assert(WSAStartup(MAKEWORD(2,2),&winsock)==0);
 {
  BindingProbe original,replacement;BlockedWindow target;
  std::thread ui([&]{target.run(desktop);});assert(WaitForSingleObject(target.visible,3000)==WAIT_OBJECT_0);
  TestComponent client(dll);assert(client.invoke(L"Configure",{original.port,std::wstring(64,L'0')}));
  auto start=GetTickCount64();auto id=client.begin();assert(WaitForSingleObject(target.entered,3000)==WAIT_OBJECT_0);
  // Keep the original named semaphore alive so its release proves that this worker really drained.
  HANDLE gate=OpenSemaphoreW(SYNCHRONIZE|SEMAPHORE_MODIFY_STATE,FALSE,captureKernelName(L"-gate").c_str());
  assert(gate&&WaitForSingleObject(gate,0)==WAIT_TIMEOUT);
  std::wstring result;
  assert(client.invoke(L"PollScreenshot",{id},&result)&&result.find(L"capture_pending")!=std::wstring::npos);
  bool poison=std::string(mode)=="--poison-probe";
  if(poison){
   while(client.invoke(L"PollScreenshot",{id},&result)){
    assert(result.find(L"capture_pending")!=std::wstring::npos&&GetTickCount64()-start<8500);Sleep(20);
   }
   assert(GetTickCount64()-start>=4900);
   assert(client.invoke(L"CancelScreenshot"));
   assert(!client.invoke(L"BeginScreenshot"));
  }else{
   assert(client.invoke(L"Configure",{replacement.port,std::wstring(64,L'1')}));
   assert(!client.invoke(L"PollScreenshot",{id}));
  }
  SetEvent(target.resume);
  assert(WaitForSingleObject(gate,4000)==WAIT_OBJECT_0);
  assert(ReleaseSemaphore(gate,1,nullptr));
  unsigned drainedPrints=target.prints.load();assert(drainedPrints>0);
  if(poison){
   assert(!client.invoke(L"BeginScreenshot"));
   TestComponent fresh(dll);assert(fresh.invoke(L"Configure",{replacement.port,std::wstring(64,L'1')}));
   assert(!fresh.invoke(L"BeginScreenshot"));assert(target.prints.load()==drainedPrints);
  }else{
   assert(!client.invoke(L"PollScreenshot",{id}));
   ResetEvent(target.entered);
   auto fresh=client.begin();assert(fresh!=id);
   assert(WaitForSingleObject(target.entered,3000)==WAIT_OBJECT_0);
   assert(!client.invoke(L"PollScreenshot",{id}));
   assert(client.invoke(L"CancelScreenshot"));
   assert(WaitForSingleObject(gate,4000)==WAIT_OBJECT_0);assert(ReleaseSemaphore(gate,1,nullptr));
  }
  CloseHandle(gate);
  assert(!original.contacted()&&!replacement.contacted());
  assert(PostMessageW(target.window,WM_CLOSE,0,0));ui.join();
 }
 WSACleanup();FreeLibrary(dll);return 0;
}
int main(int argc,char** argv){
 if(argc==3 && (std::string(argv[1])=="--poison-probe" || std::string(argv[1])=="--rebind-probe"))return lifecycleProbe(argv[1],argv[2]);
 if(argc>1){assert(argc==5&&std::string(argv[1])=="--desktop-probe");return desktopProbe(argc,argv);}

 DWORD partialBlack[]={0x80800000,0,0xff141edc},partialWhite[]={0x80800000,0x00ffffff,0xff141edc};reconstructPrintedAlpha(partialBlack,partialWhite,3);assert(partialBlack[0]==0x80800000&&partialBlack[1]==0&&partialBlack[2]==0xff141edc);
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
