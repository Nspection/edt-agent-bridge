// SPDX-License-Identifier: AGPL-3.0-only
// Native API transport only. BSL always runs in 1C's own form/timer context.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <objidl.h>
#include <algorithm>
namespace Gdiplus {using std::min;using std::max;}
#include <gdiplus.h>
#include <string>
#include <stdexcept>
#include <vector>
#include <cwchar>
#include <set>
#include <memory>
#include "ComponentBase.h"
#include "IMemoryManager.h"
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")
namespace {
void trace(const char* message){
#ifdef AGENT_HOST_DIAGNOSTICS
 wchar_t folder[MAX_PATH];GetTempPathW(MAX_PATH,folder);std::wstring path=std::wstring(folder)+L"EDT-Agent-Host-native.log";
 HANDLE f=CreateFileW(path.c_str(),FILE_APPEND_DATA,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
 if(f!=INVALID_HANDLE_VALUE){DWORD written;WriteFile(f,message,(DWORD)strlen(message),&written,nullptr);WriteFile(f,"\n",1,&written,nullptr);CloseHandle(f);}
#endif
}

constexpr size_t MaxBody=2*1024*1024;
struct Handle { HINTERNET h; ~Handle(){if(h)WinHttpCloseHandle(h);} };
std::string utf8(const std::wstring& v){
 int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,v.data(),(int)v.size(),nullptr,0,nullptr,nullptr);
 if(!n && !v.empty())throw std::runtime_error("Invalid UTF16");
 std::string s(n,0);WideCharToMultiByte(CP_UTF8,0,v.data(),(int)v.size(),s.data(),n,nullptr,nullptr);return s;
}
std::wstring wide(const std::string& v){
 int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,v.data(),(int)v.size(),nullptr,0);
 if(!n && !v.empty())throw std::runtime_error("Invalid UTF8");
 std::wstring s(n,0);MultiByteToWideChar(CP_UTF8,0,v.data(),(int)v.size(),s.data(),n);return s;
}
constexpr size_t MaxSnapshot=16*1024*1024;
struct WindowSurface {HWND window;HWND owner;DWORD thread;RECT rect;std::wstring type;};
struct CaptureTarget {HWND window=nullptr;LONG area=0;};
BOOL CALLBACK ownWindow(HWND w,LPARAM data){DWORD pid=0;GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))return TRUE;RECT r{};if(!GetClientRect(w,&r))return TRUE;LONG area=(r.right-r.left)*(r.bottom-r.top);auto t=(CaptureTarget*)data;if(!GetWindow(w,GW_OWNER)&&area>t->area){t->window=w;t->area=area;}return TRUE;}
WindowSurface surface(HWND w){DWORD pid=0;DWORD thread=GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))throw std::runtime_error("Own window changed");wchar_t name[256]{};if(!GetClassNameW(w,name,256))throw std::runtime_error("Window class unavailable");RECT r{};if(!GetWindowRect(w,&r))throw std::runtime_error("Window bounds unavailable");return {w,GetWindow(w,GW_OWNER),thread,r,name};}
struct SurfaceList {HWND main;std::vector<WindowSurface> windows;};
BOOL CALLBACK collectSurface(HWND w,LPARAM data){auto list=(SurfaceList*)data;DWORD pid=0;GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))return TRUE;LONG_PTR style=GetWindowLongPtrW(w,GWL_STYLE);if(w!=list->main&&!(style&WS_POPUP)&&GetAncestor(w,GA_ROOTOWNER)!=list->main)return TRUE;try{if(list->windows.size()>=16)throw std::runtime_error("Too many own windows");list->windows.push_back(surface(w));}catch(...){list->windows.clear();return FALSE;}return TRUE;}
SurfaceList ownSurfaces(){CaptureTarget root;EnumWindows(ownWindow,(LPARAM)&root);if(!root.window)throw std::runtime_error("No visible own application window");SurfaceList list{root.window,{}};EnumWindows(collectSurface,(LPARAM)&list);if(list.windows.empty())throw std::runtime_error("Own window inventory unavailable");return list;}
bool unchanged(const WindowSurface& before){try{auto after=surface(before.window);return after.owner==before.owner&&after.thread==before.thread&&after.type==before.type&&EqualRect(&before.rect,&after.rect);}catch(...){return false;}}
std::string encodePng(HBITMAP bitmap){
 Gdiplus::GdiplusStartupInput input;ULONG_PTR handle=0;if(Gdiplus::GdiplusStartup(&handle,&input,nullptr)!=Gdiplus::Ok)throw std::runtime_error("PNG encoder unavailable");
 struct Startup {ULONG_PTR handle;~Startup(){Gdiplus::GdiplusShutdown(handle);}} startup{handle};
 struct Stream {IStream* value=nullptr;~Stream(){if(value)value->Release();}} stream;
 if(FAILED(CreateStreamOnHGlobal(nullptr,TRUE,&stream.value)))throw std::runtime_error("Cannot allocate PNG stream");
 Gdiplus::Bitmap picture(bitmap,nullptr);CLSID png={0x557cf406,0x1a04,0x11d3,{0x9a,0x73,0x00,0x00,0xf8,0x1e,0xf3,0x2e}};
 if(picture.Save(stream.value,&png,nullptr)!=Gdiplus::Ok)throw std::runtime_error("Cannot encode PNG");
 STATSTG stat{};if(FAILED(stream.value->Stat(&stat,STATFLAG_NONAME))||stat.cbSize.QuadPart>MaxSnapshot)throw std::runtime_error("Capture too large");
 LARGE_INTEGER zero{};stream.value->Seek(zero,STREAM_SEEK_SET,nullptr);std::string bytes((size_t)stat.cbSize.QuadPart,0);ULONG read=0;
 if(FAILED(stream.value->Read(bytes.data(),(ULONG)bytes.size(),&read))||read!=bytes.size())throw std::runtime_error("Cannot read PNG");return bytes;
}

BOOL CALLBACK ownChild(HWND child,LPARAM data){DWORD pid=0;GetWindowThreadProcessId(child,&pid);if(IsWindowVisible(child)&&pid!=GetCurrentProcessId()){*(bool*)data=false;return FALSE;}return TRUE;}
struct Selected {HDC dc;HGDIOBJ prior;Selected(HDC d,HGDIOBJ object):dc(d),prior(SelectObject(d,object)){}void restore(){if(prior){SelectObject(dc,prior);prior=nullptr;}}~Selected(){restore();}};
struct Captured {std::string png;size_t windows=0,popups=0;bool armed=false;ULONGLONG capturedAt=0;};
ULONGLONG unixMillis(){FILETIME ft;GetSystemTimeAsFileTime(&ft);ULARGE_INTEGER n;n.LowPart=ft.dwLowDateTime;n.HighPart=ft.dwHighDateTime;return (n.QuadPart-116444736000000000ULL)/10000;}
Captured captureWindows(HWND required=nullptr){
 auto list=ownSurfaces();if(required&&std::none_of(list.windows.begin(),list.windows.end(),[&](const WindowSurface& w){return w.window==required;}))throw std::runtime_error("Expected popup disappeared");ULONGLONG capturedAt=unixMillis();RECT bounds=list.windows[0].rect;for(auto& w:list.windows){RECT next;UnionRect(&next,&bounds,&w.rect);bounds=next;}
 int width=bounds.right-bounds.left,height=bounds.bottom-bounds.top;if(width<1||height<1||width>8192||height>8192||(size_t)width*height>24*1024*1024)throw std::runtime_error("Composite dimensions exceed limit");
 struct DC {HWND w;HDC value;~DC(){if(value)ReleaseDC(w,value);}} source{list.main,GetWindowDC(list.main)};
 struct MemDC {HDC value;~MemDC(){if(value)DeleteDC(value);}} canvas{source.value?CreateCompatibleDC(source.value):nullptr};
 struct Bitmap {HBITMAP value;~Bitmap(){if(value)DeleteObject(value);}} bitmap{canvas.value?CreateCompatibleBitmap(source.value,width,height):nullptr};if(!bitmap.value)throw std::runtime_error("Cannot allocate composite");Selected canvasSelection(canvas.value,bitmap.value);RECT fill{0,0,width,height};FillRect(canvas.value,&fill,(HBRUSH)GetStockObject(BLACK_BRUSH));
 for(auto it=list.windows.rbegin();it!=list.windows.rend();++it){auto& w=*it;bool childrenOwned=true;EnumChildWindows(w.window,ownChild,(LPARAM)&childrenOwned);if(!childrenOwned)throw std::runtime_error("Foreign embedded window excluded from capture");if(!unchanged(w))throw std::runtime_error("Own popup disappeared before capture");int cx=w.rect.right-w.rect.left,cy=w.rect.bottom-w.rect.top;if(cx<1||cy<1)throw std::runtime_error("Invalid own window size");MemDC memory{CreateCompatibleDC(source.value)};Bitmap part{memory.value?CreateCompatibleBitmap(source.value,cx,cy):nullptr};if(!part.value)throw std::runtime_error("Cannot allocate own surface");Selected partSelection(memory.value,part.value);DWORD_PTR rendered=0;BOOL printed=SendMessageTimeoutW(w.window,WM_PRINT,(WPARAM)memory.value,PRF_CLIENT|PRF_NONCLIENT|PRF_CHILDREN|PRF_ERASEBKGND,SMTO_ABORTIFHUNG|SMTO_BLOCK,250,&rendered)!=0;if(!printed||!unchanged(w))throw std::runtime_error("Own popup did not render or changed");if(!BitBlt(canvas.value,w.rect.left-bounds.left,w.rect.top-bounds.top,cx,cy,memory.value,0,0,SRCCOPY))throw std::runtime_error("Cannot compose own windows");partSelection.restore();}
 canvasSelection.restore();return {encodePng(bitmap.value),list.windows.size(),list.windows.size()-1,false,capturedAt};
}
struct PopupObserver;thread_local PopupObserver* observer=nullptr;
struct PopupObserver {
 HWINEVENTHOOK show=nullptr,menu=nullptr;ULONGLONG deadline=0;bool busy=false,required=false;std::set<HWND> baseline;std::unique_ptr<Captured> captured;
 static void CALLBACK event(HWINEVENTHOOK,DWORD kind,HWND hwnd,LONG object,LONG,DWORD,DWORD){
  auto self=observer;if(!self||self->busy||!hwnd||(kind==EVENT_OBJECT_SHOW&&object!=OBJID_WINDOW)||GetTickCount64()>self->deadline||self->baseline.count(hwnd))return;
  DWORD pid=0;GetWindowThreadProcessId(hwnd,&pid);LONG_PTR style=GetWindowLongPtrW(hwnd,GWL_STYLE);
  // A newly opened document/form or child control must not consume a later menu capture.
  if(pid!=GetCurrentProcessId()||(style&WS_CHILD)||!(style&WS_POPUP)||(style&WS_CAPTION)||!IsWindowVisible(hwnd))return;
  self->busy=true;try{auto list=ownSurfaces();if(hwnd!=list.main){auto value=captureWindows(hwnd);if(value.popups==0)throw std::runtime_error("Expected popup absent");value.armed=true;self->captured=std::make_unique<Captured>(std::move(value));self->cancelHooks();}}catch(...){/* A later own show event may provide the stable popup. */}self->busy=false;
 }
 void cancelHooks(){if(show)UnhookWinEvent(show);if(menu)UnhookWinEvent(menu);show=nullptr;menu=nullptr;if(observer==this)observer=nullptr;}
 void cancel(){cancelHooks();captured.reset();baseline.clear();deadline=0;required=false;}
 void arm(bool popup){if(observer&&observer!=this)throw std::runtime_error("Another capture owns this UI thread");cancel();required=popup;auto list=ownSurfaces();for(auto& w:list.windows)baseline.insert(w.window);deadline=GetTickCount64()+5000;HMODULE module=nullptr;if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,(LPCWSTR)&PopupObserver::event,&module))throw std::runtime_error("Capture callback module unavailable");observer=this;show=SetWinEventHook(EVENT_OBJECT_SHOW,EVENT_OBJECT_SHOW,module,&PopupObserver::event,GetCurrentProcessId(),GetCurrentThreadId(),WINEVENT_INCONTEXT);menu=SetWinEventHook(EVENT_SYSTEM_MENUPOPUPSTART,EVENT_SYSTEM_MENUPOPUPSTART,module,&PopupObserver::event,GetCurrentProcessId(),GetCurrentThreadId(),WINEVENT_INCONTEXT);if(!show||!menu){cancel();throw std::runtime_error("Own popup observer unavailable");}}
 std::wstring state(){return std::wstring(L"{\"capture_ready\":")+(captured?L"true":L"false")+L",\"capture_expired\":"+((!deadline||GetTickCount64()>deadline)?L"true":L"false")+L"}";}
 Captured take(){if(!deadline)throw std::runtime_error("Capture not armed");if(captured){Captured value=std::move(*captured);cancel();return value;}if(required){cancel();throw std::runtime_error("popup_not_observed");}cancel();return captureWindows();}
 ~PopupObserver(){cancel();}
};
class AgentHost final : public IComponentBase {
 IMemoryManager* memory=nullptr; INTERNET_PORT port=0; std::wstring token;PopupObserver popup;
 static constexpr const wchar_t* methods[]={L"Configure",L"Poll",L"Complete",L"Hello",L"Stop",L"Screenshot",L"Capabilities",L"CaptureState",L"ArmScreenshot",L"CancelScreenshot",L"TakeArmedScreenshot"};
 std::wstring str(tVariant& v){if(v.vt!=VTYPE_PWSTR || v.wstrLen>MaxBody)throw std::runtime_error("Expected bounded string");return {v.pwstrVal,v.wstrLen};}
 void result(tVariant* v,const std::wstring& text){
  if(!memory || text.size()>MaxBody)throw std::runtime_error("Result too large");
  void* p=nullptr;if(!memory->AllocMemory(&p,(unsigned long)((text.size()+1)*sizeof(wchar_t))))throw std::bad_alloc();
  memcpy(p,text.c_str(),(text.size()+1)*sizeof(wchar_t));memset(v,0,sizeof(*v));v->vt=VTYPE_PWSTR;v->pwstrVal=(WCHAR_T*)p;v->wstrLen=(uint32_t)text.size();
 }
 std::wstring requestBytes(const wchar_t* path,const std::string& bytes,bool snapshot=false,const std::string& metadata=""){
  if(!port || token.empty())throw std::runtime_error("Not configured");
  if(bytes.size()>(snapshot?MaxSnapshot:MaxBody))throw std::runtime_error("Request too large");
  Handle session{WinHttpOpen(L"EDT-Agent-Host/1",WINHTTP_ACCESS_TYPE_NO_PROXY,WINHTTP_NO_PROXY_NAME,WINHTTP_NO_PROXY_BYPASS,0)};
  if(!session.h)throw std::runtime_error("WinHTTP unavailable");
  WinHttpSetTimeouts(session.h,1500,1500,1500,1500);
  Handle connection{WinHttpConnect(session.h,L"127.0.0.1",port,0)};
  Handle req{connection.h?WinHttpOpenRequest(connection.h,L"POST",path,nullptr,WINHTTP_NO_REFERER,WINHTTP_DEFAULT_ACCEPT_TYPES,0):nullptr};
  if(!req.h)throw std::runtime_error("Cannot connect host");
  DWORD redirect=WINHTTP_OPTION_REDIRECT_POLICY_NEVER;WinHttpSetOption(req.h,WINHTTP_OPTION_REDIRECT_POLICY,&redirect,sizeof(redirect));
  auto headers=std::wstring(snapshot?L"Content-Type: image/png\r\nAuthorization: Bearer ":L"Content-Type: application/json\r\nAuthorization: Bearer ")+token+L"\r\n";if(snapshot&&!metadata.empty())headers+=L"X-Bridge-Capture: "+wide(metadata)+L"\r\n";
  if(!WinHttpSendRequest(req.h,headers.c_str(),(DWORD)-1L,(void*)bytes.data(),(DWORD)bytes.size(),(DWORD)bytes.size(),0)||!WinHttpReceiveResponse(req.h,nullptr))throw std::runtime_error("Host unavailable");
  DWORD status=0,len=sizeof(status);WinHttpQueryHeaders(req.h,WINHTTP_QUERY_STATUS_CODE|WINHTTP_QUERY_FLAG_NUMBER,WINHTTP_HEADER_NAME_BY_INDEX,&status,&len,WINHTTP_NO_HEADER_INDEX);
  if(status!=200)throw std::runtime_error("Host rejected request");
  std::string response;DWORD available=0,read=0;
  while(WinHttpQueryDataAvailable(req.h,&available)&&available){
   if(response.size()+available>MaxBody)throw std::runtime_error("Response too large");
   std::vector<char> buf(available);if(!WinHttpReadData(req.h,buf.data(),available,&read))throw std::runtime_error("Read failed");response.append(buf.data(),read);
  }
  return wide(response);
 }
public:
 bool ADDIN_API Init(void*) override{trace("Init");return true;}
 bool ADDIN_API setMemManager(void* p) override{trace("setMemManager");memory=(IMemoryManager*)p;return memory!=nullptr;}
 long ADDIN_API GetInfo() override{return 2000;}
 void ADDIN_API Done() override{popup.cancel();port=0;token.clear();}
 bool ADDIN_API RegisterExtensionAs(WCHAR_T** out) override{
  trace("RegisterExtensionAs");void* p=nullptr;const wchar_t* s=L"AgentHost";if(!memory||!memory->AllocMemory(&p,20))return false;memcpy(p,s,20);*out=(WCHAR_T*)p;return true;
 }
 long ADDIN_API GetNProps() override{return 0;}
 long ADDIN_API FindProp(const WCHAR_T*) override{return -1;}
 const WCHAR_T* ADDIN_API GetPropName(long,long) override{return nullptr;}
 bool ADDIN_API GetPropVal(long,tVariant*) override{return false;}
 bool ADDIN_API SetPropVal(long,tVariant*) override{return false;}
 bool ADDIN_API IsPropReadable(long) override{return false;}
 bool ADDIN_API IsPropWritable(long) override{return false;}
 long ADDIN_API GetNMethods() override{return 11;}
 long ADDIN_API FindMethod(const WCHAR_T* name) override{for(long i=0;i<11;i++)if(!_wcsicmp(name,methods[i]))return i;return -1;}
 const WCHAR_T* ADDIN_API GetMethodName(long i,long alias) override{
  trace("GetMethodName");if(i<0||i>=11||alias<0||alias>1||!memory)return nullptr;
  void* p=nullptr;size_t bytes=(wcslen(methods[i])+1)*sizeof(wchar_t);
  if(!memory->AllocMemory(&p,(unsigned long)bytes))return nullptr;memcpy(p,methods[i],bytes);return (WCHAR_T*)p;
 }
 long ADDIN_API GetNParams(long i) override{return i==0?2:(i==2||i==3||i==8?1:0);}
 bool ADDIN_API GetParamDefValue(long,long,tVariant*) override{return false;}
 bool ADDIN_API HasRetVal(long i) override{return i>=0&&i<11;}
 bool ADDIN_API CallAsProc(long i,tVariant* p,long n) override{tVariant v{};bool ok=CallAsFunc(i,&v,p,n);if(ok&&v.vt==VTYPE_PWSTR){void* ptr=v.pwstrVal;memory->FreeMemory(&ptr);}return ok;}
 bool ADDIN_API CallAsFunc(long i,tVariant* ret,tVariant* p,long n) override{
  try{
   if(i<0||i>=11||n!=GetNParams(i))return false;
   if(i==0){auto s=str(p[0]);size_t end=0;int value=std::stoi(s,&end);auto secret=str(p[1]);
    if(end!=s.size()||value<1024||value>65535||secret.size()!=64||secret.find_first_not_of(L"0123456789abcdef")!=std::wstring::npos)return false;
    port=(INTERNET_PORT)value;token=secret;result(ret,L"configured");
   }else if(i==6){result(ret,L"{\"protocol\":2,\"features\":[\"screenshot_png\",\"own_window_composite_v1\",\"armed_popup_capture_v1\"]}");}
   else if(i==5||i==10){if(!port||token.empty())throw std::runtime_error("Not configured");auto shot=i==10?popup.take():captureWindows();std::string meta="{\"scope\":\"own_window_composite\",\"window_count\":"+std::to_string(shot.windows)+",\"popup_count\":"+std::to_string(shot.popups)+",\"armed\":"+(shot.armed?"true":"false")+",\"pixel_content_verified\":false,\"captured_at_ms\":"+std::to_string(shot.capturedAt)+"}";result(ret,requestBytes(L"/snapshot",shot.png,true,meta));}
   else if(i==7){result(ret,popup.state());}
   else if(i==8){auto mode=str(p[0]);if(mode!=L"popup"&&mode!=L"optional")throw std::runtime_error("Unsupported capture expectation");popup.arm(mode==L"popup");result(ret,L"armed");}
   else if(i==9){popup.cancel();result(ret,L"cancelled");}
   else if(i==4){Done();result(ret,L"stopped");}
   else result(ret,requestBytes(i==1?L"/poll":i==2?L"/result":L"/hello",utf8(i==1?L"{}":str(p[0]))));
   return true;
  }catch(...){return false;}
 }
 void ADDIN_API SetLocale(const WCHAR_T*) override{}
};
}
extern "C" long GetClassObject(const WCHAR_T* name,IComponentBase** out){trace("GetClassObject");try{if(!out||*out||wcscmp(name,L"AgentHost"))return 0;*out=new AgentHost();return 1;}catch(...){return 0;}}
extern "C" long DestroyObject(IComponentBase** out){if(!out||!*out)return -1;delete *out;*out=nullptr;return 0;}
extern "C" const WCHAR_T* GetClassNames(){trace("GetClassNames");return L"AgentHost";}
extern "C" AppCapabilities SetPlatformCapabilities(AppCapabilities){trace("SetPlatformCapabilities");return eAppCapabilities1;}
