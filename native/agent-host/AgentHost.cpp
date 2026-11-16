// SPDX-License-Identifier: AGPL-3.0-only
// Native API transport only. BSL always runs in 1C's own form/timer context.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <dwmapi.h>
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
#include <atomic>
#include <thread>
#include <mutex>
#include "ComponentBase.h"
#include "IMemoryManager.h"
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "msimg32.lib")
#pragma comment(lib, "dwmapi.lib")
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
struct PhysicalCoordinates {
 using SetContext=DPI_AWARENESS_CONTEXT(WINAPI*)(DPI_AWARENESS_CONTEXT);SetContext set=nullptr;DPI_AWARENESS_CONTEXT prior=nullptr;
 PhysicalCoordinates(){set=(SetContext)GetProcAddress(GetModuleHandleW(L"user32.dll"),"SetThreadDpiAwarenessContext");if(set)prior=set(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);}
 ~PhysicalCoordinates(){if(set&&prior)set(prior);}
};
struct WindowSurface {HWND window;HWND owner;DWORD thread;RECT rect;std::wstring type;RECT frame;};
struct CaptureTarget {HWND window=nullptr;LONG area=0;};
BOOL CALLBACK ownWindow(HWND w,LPARAM data){DWORD pid=0;GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))return TRUE;RECT r{};if(!GetClientRect(w,&r))return TRUE;LONG area=(r.right-r.left)*(r.bottom-r.top);auto t=(CaptureTarget*)data;if(!GetWindow(w,GW_OWNER)&&area>t->area){t->window=w;t->area=area;}return TRUE;}
WindowSurface surface(HWND w){DWORD pid=0;DWORD thread=GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))throw std::runtime_error("Own window changed");wchar_t name[256]{};if(!GetClassNameW(w,name,256))throw std::runtime_error("Window class unavailable");PhysicalCoordinates coordinates;RECT r{};if(!GetWindowRect(w,&r))throw std::runtime_error("Window bounds unavailable");RECT frame=r,candidate{};if(SUCCEEDED(DwmGetWindowAttribute(w,DWMWA_EXTENDED_FRAME_BOUNDS,&candidate,sizeof(candidate)))&&candidate.right>candidate.left&&candidate.bottom>candidate.top&&candidate.left>=r.left&&candidate.top>=r.top&&candidate.right<=r.right&&candidate.bottom<=r.bottom)frame=candidate;return {w,GetWindow(w,GW_OWNER),thread,r,name,frame};}
struct SurfaceList {HWND main;std::vector<WindowSurface> windows;};
BOOL CALLBACK collectSurface(HWND w,LPARAM data){auto list=(SurfaceList*)data;DWORD pid=0;GetWindowThreadProcessId(w,&pid);if(pid!=GetCurrentProcessId()||!IsWindowVisible(w)||IsIconic(w))return TRUE;LONG_PTR style=GetWindowLongPtrW(w,GWL_STYLE);DWORD rootPid=0;DWORD rootThread=GetWindowThreadProcessId(list->main,&rootPid);if(GetWindowThreadProcessId(w,&pid)!=rootThread)return TRUE;if(w!=list->main&&!(style&WS_POPUP)&&GetAncestor(w,GA_ROOTOWNER)!=list->main)return TRUE;try{if(list->windows.size()>=16)throw std::runtime_error("Too many own windows");list->windows.push_back(surface(w));}catch(...){list->windows.clear();return FALSE;}return TRUE;}
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
std::atomic_bool capturePoisoned{false};
std::wstring captureKernelName(const wchar_t* suffix){FILETIME created{},exited{},kernel{},user{};if(!GetProcessTimes(GetCurrentProcess(),&created,&exited,&kernel,&user))throw std::runtime_error("Own process identity unavailable");return L"Local\\EDTBridgeCapture-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(created.dwHighDateTime)+L"-"+std::to_wstring(created.dwLowDateTime)+suffix;}
void poisonCapture(){if(!capturePoisoned.exchange(true)){HANDLE event=CreateEventW(nullptr,TRUE,FALSE,captureKernelName(L"-failed").c_str());if(event)SetEvent(event);/* Intentionally process-owned: survives a DLL reload, released only at process exit. */}}
struct CaptureBusy : std::runtime_error {CaptureBusy():std::runtime_error("Own capture is draining") {}};
struct CaptureGate {
 HANDLE gate=nullptr,event=nullptr;bool held=false;
 void release(){if(held)ReleaseSemaphore(gate,1,nullptr);if(gate)CloseHandle(gate);if(event)CloseHandle(event);gate=nullptr;event=nullptr;held=false;}
 CaptureGate(){gate=CreateSemaphoreW(nullptr,1,1,captureKernelName(L"-gate").c_str());event=CreateEventW(nullptr,TRUE,FALSE,captureKernelName(L"-failed").c_str());if(!gate||!event){release();throw std::runtime_error("Capture gate unavailable");}if(WaitForSingleObject(event,0)==WAIT_OBJECT_0){release();throw std::runtime_error("Capture unavailable until owned process exit");}held=WaitForSingleObject(gate,0)==WAIT_OBJECT_0;if(!held){release();throw CaptureBusy();}}
 ~CaptureGate(){release();}
 CaptureGate(const CaptureGate&)=delete;CaptureGate& operator=(const CaptureGate&)=delete;
};
// Reconstruct only this UI thread's window-print output; no screen pixels are read.
// GDI does not preserve the high byte, so derive source-over alpha from two backdrops.
struct PrintedSurface {
 HDC dc=nullptr;HBITMAP bitmap=nullptr;HGDIOBJ prior=nullptr;DWORD* pixels=nullptr;
 void release(){if(prior&&dc)SelectObject(dc,prior);if(bitmap)DeleteObject(bitmap);if(dc)DeleteDC(dc);prior=nullptr;bitmap=nullptr;dc=nullptr;}
 PrintedSurface(HDC reference,int width,int height){
  dc=CreateCompatibleDC(reference);BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=width;info.bmiHeader.biHeight=-height;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;
  if(dc)bitmap=CreateDIBSection(reference,&info,DIB_RGB_COLORS,(void**)&pixels,nullptr,0);
  if(bitmap)prior=SelectObject(dc,bitmap);
  if(!dc||!bitmap||!pixels||!prior||prior==HGDI_ERROR){release();throw std::runtime_error("Cannot allocate own print surface");}
 }
 ~PrintedSurface(){release();}
 PrintedSurface(const PrintedSurface&)=delete;PrintedSurface& operator=(const PrintedSurface&)=delete;
};
void printMattePass(const WindowSurface& w,PrintedSurface& surface){
 HDC dc=surface.dc;
 bool childrenOwned=true;EnumChildWindows(w.window,ownChild,(LPARAM)&childrenOwned);
 if(!childrenOwned||!unchanged(w))throw std::runtime_error("Own print surface identity changed");
 SendMessageW(w.window,WM_PRINT,(WPARAM)dc,PRF_CLIENT|PRF_NONCLIENT|PRF_CHILDREN|PRF_ERASEBKGND);
 GdiFlush();childrenOwned=true;EnumChildWindows(w.window,ownChild,(LPARAM)&childrenOwned);
 if(!childrenOwned||!unchanged(w))throw std::runtime_error("Own print surface changed during rendering");
}
struct PrintInconclusive : std::runtime_error {using std::runtime_error::runtime_error;};
void reconstructPrintedAlpha(DWORD* black,const DWORD* white,size_t count){
 bool explicitAlpha=false,allZero=true;
 for(size_t i=0;i<count;i++){explicitAlpha|=(black[i]>>24)!=0||(white[i]>>24)!=0;allZero&=black[i]==0&&white[i]==0;}
 if(allZero)throw PrintInconclusive("Window print inconclusive: transparent raw surface or empty black output");
 bool informative=false;
 for(size_t i=0;i<count;i++){
  DWORD b=black[i],v=white[i];int supplied=b>>24;
  if(b==v&&supplied>0){for(int channel=0;channel<3;channel++)if(int((b>>(channel*8))&255)>supplied)throw std::runtime_error("Window print provided unsupported non-premultiplied alpha");informative=true;continue;}
  if(b==0&&v==0&&explicitAlpha){black[i]=0;continue;}
  int delta[3];for(int channel=0;channel<3;channel++)delta[channel]=int((v>>(channel*8))&255)-int((b>>(channel*8))&255);
  int low=*std::min_element(delta,delta+3),high=*std::max_element(delta,delta+3);
  if(low<0||high>255||high-low>1)throw std::runtime_error("Own window print changed between matte passes");
  int alpha=255-(delta[0]+delta[1]+delta[2]+1)/3;DWORD premultiplied=(DWORD)alpha<<24;
  for(int channel=0;channel<3;channel++){int color=(b>>(channel*8))&255;if(color>alpha+1)throw std::runtime_error("Window print does not use supported source-over blending");premultiplied|=(DWORD)std::min(color,alpha)<<(channel*8);}
  informative|=alpha!=0;black[i]=premultiplied;
 }

 if(!informative)throw PrintInconclusive("Window print inconclusive: untouched or fully transparent surface");
}
bool composePrintedMatte(const WindowSurface& w,HDC reference,HDC canvas,int x,int y,int width,int height,BYTE constantAlpha=255,bool keyed=false,COLORREF colorKey=0){
 PrintedSurface black(reference,width,height),white(reference,width,height);size_t count=(size_t)width*height;
 std::fill_n(black.pixels,count,0u);std::fill_n(white.pixels,count,0x00ffffffu);printMattePass(w,black);printMattePass(w,white);
 reconstructPrintedAlpha(black.pixels,white.pixels,count);
 if(keyed){for(size_t i=0;i<count;i++){DWORD pixel=black.pixels[i];int alpha=pixel>>24;if(alpha){int b=int(pixel&255)*255/alpha,g=int((pixel>>8)&255)*255/alpha,red=int((pixel>>16)&255)*255/alpha;if(RGB(red,g,b)==colorKey)black.pixels[i]=0;}}}

 BLENDFUNCTION blend{AC_SRC_OVER,0,constantAlpha,AC_SRC_ALPHA};return AlphaBlend(canvas,x,y,width,height,black.dc,0,0,width,height,blend)!=FALSE;
}
struct Captured {std::string png;size_t windows=0,popups=0;bool armed=false;ULONGLONG capturedAt=0;size_t excludedPassive=0;};
ULONGLONG unixMillis(){FILETIME ft;GetSystemTimeAsFileTime(&ft);ULARGE_INTEGER n;n.LowPart=ft.dwLowDateTime;n.HighPart=ft.dwHighDateTime;return (n.QuadPart-116444736000000000ULL)/10000;}
Captured captureWindows(HWND required=nullptr,bool leased=false){
 std::unique_ptr<CaptureGate> gate;if(!leased)gate=std::make_unique<CaptureGate>();
 if(capturePoisoned.load())throw std::runtime_error("Capture unavailable until timed-out owned process exits");
 auto list=ownSurfaces();if(required&&std::none_of(list.windows.begin(),list.windows.end(),[&](const WindowSurface& w){return w.window==required;}))throw std::runtime_error("Expected popup disappeared");ULONGLONG capturedAt=unixMillis();RECT bounds=list.windows[0].frame;for(auto& w:list.windows){RECT next;UnionRect(&next,&bounds,&w.frame);bounds=next;}
 size_t printArea=0;for(auto& w:list.windows){size_t area=(size_t)(w.rect.right-w.rect.left)*(w.rect.bottom-w.rect.top);if(area>24*1024*1024||printArea>24*1024*1024-area)throw std::runtime_error("Own surface print area exceeds limit");printArea+=area;}
 int width=bounds.right-bounds.left,height=bounds.bottom-bounds.top;if(width<1||height<1||width>8192||height>8192||(size_t)width*height>24*1024*1024)throw std::runtime_error("Composite dimensions exceed limit");
 struct DC {HWND w;HDC value;~DC(){if(value)ReleaseDC(w,value);}} source{list.main,GetWindowDC(list.main)};
 struct MemDC {HDC value;~MemDC(){if(value)DeleteDC(value);}} canvas{source.value?CreateCompatibleDC(source.value):nullptr};
 struct Bitmap {HBITMAP value;~Bitmap(){if(value)DeleteObject(value);}} bitmap{canvas.value?CreateCompatibleBitmap(source.value,width,height):nullptr};if(!bitmap.value)throw std::runtime_error("Cannot allocate composite");Selected canvasSelection(canvas.value,bitmap.value);RECT fill{0,0,width,height};FillRect(canvas.value,&fill,(HBRUSH)GetStockObject(BLACK_BRUSH));
 size_t rendered=0,excluded=0;RECT included{};
 for(auto it=list.windows.rbegin();it!=list.windows.rend();++it){auto& w=*it;if(!leased&&w.thread!=GetCurrentThreadId())throw std::runtime_error("Use asynchronous capture for another own UI thread");bool childrenOwned=true;EnumChildWindows(w.window,ownChild,(LPARAM)&childrenOwned);if(!childrenOwned)throw std::runtime_error("Foreign embedded window excluded from capture");if(!unchanged(w))throw std::runtime_error("Own surface changed before capture");int cx=w.rect.right-w.rect.left,cy=w.rect.bottom-w.rect.top;if(cx<1||cy<1)throw std::runtime_error("Invalid own window size");int x=w.rect.left-bounds.left,y=w.rect.top-bounds.top;
  struct Region {HRGN value;~Region(){if(value)DeleteObject(value);}} region{CreateRectRgn(0,0,0,0)};
  if(!region.value)throw std::runtime_error("Cannot allocate window region");
  int shape=GetWindowRgn(w.window,region.value),saved=SaveDC(canvas.value);if(!saved)throw std::runtime_error("Cannot save compositor state");
  Region visible{CreateRectRgn(w.frame.left-bounds.left,w.frame.top-bounds.top,w.frame.right-bounds.left,w.frame.bottom-bounds.top)};
  if(!visible.value){RestoreDC(canvas.value,saved);throw std::runtime_error("Cannot allocate own visible frame region");}
  if(shape!=ERROR){OffsetRgn(region.value,x,y);if(CombineRgn(visible.value,visible.value,region.value,RGN_AND)==ERROR){RestoreDC(canvas.value,saved);throw std::runtime_error("Cannot intersect own window regions");}}
  if(SelectClipRgn(canvas.value,visible.value)==ERROR){RestoreDC(canvas.value,saved);throw std::runtime_error("Cannot clip own window region");}
  COLORREF color=0;BYTE alpha=255;DWORD flags=0;bool layered=(GetWindowLongPtrW(w.window,GWL_EXSTYLE)&WS_EX_LAYERED)!=0;bool attributes=layered&&GetLayeredWindowAttributes(w.window,&color,&alpha,&flags);BOOL composed=FALSE;
  try{
   if(layered&&!attributes)composed=composePrintedMatte(w,source.value,canvas.value,x,y,cx,cy);
   else{
    PrintedSurface printed(source.value,cx,cy);
    if(!BitBlt(printed.dc,0,0,cx,cy,canvas.value,x,y,SRCCOPY))throw std::runtime_error("Cannot initialize own surface backdrop");
    printMattePass(w,printed);
    if(attributes&&(flags&LWA_COLORKEY)&&(!(flags&LWA_ALPHA)||alpha==255))composed=TransparentBlt(canvas.value,x,y,cx,cy,printed.dc,0,0,cx,cy,color);
    else if(attributes&&(flags&LWA_ALPHA)&&!(flags&LWA_COLORKEY)){BLENDFUNCTION blend{AC_SRC_OVER,0,alpha,0};composed=AlphaBlend(canvas.value,x,y,cx,cy,printed.dc,0,0,cx,cy,blend);}
    else if(attributes&&(flags&LWA_COLORKEY)&&(flags&LWA_ALPHA))throw std::runtime_error("Combined alpha and color-key surface requires a verified renderer");
    else composed=BitBlt(canvas.value,x,y,cx,cy,printed.dc,0,0,SRCCOPY);
   }
  }catch(const PrintInconclusive&){RestoreDC(canvas.value,saved);LONG_PTR ex=GetWindowLongPtrW(w.window,GWL_EXSTYLE);if(w.window!=required&&w.window!=list.main&&(ex&WS_EX_LAYERED)&&(ex&WS_EX_NOACTIVATE)){excluded++;continue;}throw;}
  catch(...){RestoreDC(canvas.value,saved);throw;}
  RestoreDC(canvas.value,saved);if(!composed)throw std::runtime_error("Cannot compose own windows");
  if(rendered==0)included=w.frame;else{RECT next;UnionRect(&next,&included,&w.frame);included=next;}rendered++;
 }
 if(rendered==0)throw std::runtime_error("No own UI surface could be rendered");
 std::string png;
 if(!EqualRect(&included,&bounds)){int cw=included.right-included.left,ch=included.bottom-included.top;MemDC cropped{CreateCompatibleDC(source.value)};Bitmap crop{cropped.value?CreateCompatibleBitmap(source.value,cw,ch):nullptr};if(!crop.value)throw std::runtime_error("Cannot allocate own frame crop");Selected selection(cropped.value,crop.value);if(!BitBlt(cropped.value,0,0,cw,ch,canvas.value,included.left-bounds.left,included.top-bounds.top,SRCCOPY))throw std::runtime_error("Cannot crop own printable UI bounds");selection.restore();png=encodePng(crop.value);}else{canvasSelection.restore();png=encodePng(bitmap.value);}
 canvasSelection.restore();return {std::move(png),rendered,rendered-1,false,capturedAt,excluded};
}
struct CaptureJob {
 struct State {std::mutex mutex;bool done=false,cancelled=false;ULONGLONG completedAt=0;std::unique_ptr<Captured> frame;std::string error;};
 std::shared_ptr<State> state;std::wstring id;ULONGLONG deadline=0;HDESK desktop=nullptr;HWND required=nullptr;std::shared_ptr<WindowSurface> expected;bool started=false;
 static std::atomic_ullong sequence;
 void launch(){
  if(started||!state)return;
  std::shared_ptr<CaptureGate> lease;try{lease=std::make_shared<CaptureGate>();}catch(const CaptureBusy&){return;}
  HMODULE module=nullptr;if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_PIN,(LPCWSTR)&captureWindows,&module))throw std::runtime_error("Capture worker module unavailable");
  auto value=state;auto target=expected;auto ownDesktop=desktop;auto window=required;
  std::thread([value,lease,target,ownDesktop,window]()mutable{
   std::unique_ptr<Captured> frame;std::string error;
   try{if(!SetThreadDesktop(ownDesktop))throw std::runtime_error("Owned capture desktop unavailable");if(target&&!unchanged(*target))throw std::runtime_error("Expected popup changed before capture");frame=std::make_unique<Captured>(captureWindows(window,true));if(target&&!unchanged(*target))throw std::runtime_error("Expected popup changed during capture");if(window){if(frame->popups==0)throw std::runtime_error("Expected popup absent");frame->armed=true;}}
   catch(const std::exception& failure){error=failure.what();}catch(...){error="Own capture failed";}
   lease.reset();std::lock_guard<std::mutex> lock(value->mutex);if(!value->cancelled){value->frame=std::move(frame);value->error=std::move(error);}value->completedAt=GetTickCount64();value->done=true;
  }).detach();started=true;
 }
 void begin(HWND window=nullptr){
  if(state)throw std::runtime_error("Capture already pending");required=window;if(window)expected=std::make_shared<WindowSurface>(surface(window));desktop=GetThreadDesktop(GetCurrentThreadId());state=std::make_shared<State>();id=std::to_wstring(++sequence);deadline=GetTickCount64()+5000;started=false;
  try{launch();}catch(...){cancel();throw;}
 }
 bool ready(){
  if(!state)throw std::runtime_error("No capture pending");
  if(GetTickCount64()>deadline){std::lock_guard<std::mutex> lock(state->mutex);if(!state->done){if(started)poisonCapture();throw std::runtime_error("Own capture deadline exceeded");}}
  launch();std::lock_guard<std::mutex> lock(state->mutex);if(state->done&&state->completedAt>deadline)throw std::runtime_error("Own capture finished after its deadline");return state->done;
 }
 bool succeeded(){if(!ready())return false;std::lock_guard<std::mutex> lock(state->mutex);return state->frame!=nullptr&&state->error.empty();}
 Captured take(const std::wstring& token){if(token!=id||!ready())throw std::runtime_error("Capture identity changed or not ready");auto value=state;state.reset();std::lock_guard<std::mutex> lock(value->mutex);if(!value->error.empty())throw std::runtime_error(value->error);if(!value->frame)throw std::runtime_error("Capture cancelled");return std::move(*value->frame);}
 void cancel(){if(state){std::lock_guard<std::mutex> lock(state->mutex);state->cancelled=true;}state.reset();expected.reset();required=nullptr;id.clear();deadline=0;started=false;}
 ~CaptureJob(){cancel();}
};
std::atomic_ullong CaptureJob::sequence{0};
struct PopupObserver;thread_local PopupObserver* observer=nullptr;
struct PopupObserver {
 HWINEVENTHOOK show=nullptr,menu=nullptr;ULONGLONG deadline=0;bool busy=false,required=false;std::set<HWND> baseline;std::vector<WindowSurface> candidates;CaptureJob pending;
 void drive(){
  if(pending.state){if(!pending.ready())return;if(pending.succeeded()){cancelHooks();return;}pending.cancel();}
  while(!candidates.empty()&&GetTickCount64()<=deadline){auto candidate=candidates.front();candidates.erase(candidates.begin());if(!unchanged(candidate))continue;try{pending.begin(candidate.window);return;}catch(const std::exception& failure){trace(failure.what());}}
 }
 static void CALLBACK event(HWINEVENTHOOK,DWORD kind,HWND hwnd,LONG object,LONG,DWORD,DWORD){
  auto self=observer;if(!self||self->busy||!hwnd||(kind==EVENT_OBJECT_SHOW&&object!=OBJID_WINDOW)||GetTickCount64()>self->deadline||self->baseline.count(hwnd))return;
  DWORD pid=0;GetWindowThreadProcessId(hwnd,&pid);LONG_PTR style=GetWindowLongPtrW(hwnd,GWL_STYLE);if(pid!=GetCurrentProcessId()||(style&WS_CHILD)||!(style&WS_POPUP)||(style&WS_CAPTION)||!IsWindowVisible(hwnd))return;
  self->busy=true;try{auto list=ownSurfaces();if(hwnd!=list.main&&self->candidates.size()<16){self->baseline.insert(hwnd);self->candidates.push_back(surface(hwnd));self->drive();}}catch(...){/* A later observed candidate can still succeed within the original deadline. */}self->busy=false;
 }
 void cancelHooks(){if(show)UnhookWinEvent(show);if(menu)UnhookWinEvent(menu);show=nullptr;menu=nullptr;if(observer==this)observer=nullptr;}
 void cancel(){cancelHooks();pending.cancel();candidates.clear();baseline.clear();deadline=0;required=false;}
 void arm(bool popup){if(observer&&observer!=this)throw std::runtime_error("Another capture owns this UI thread");cancel();required=popup;auto list=ownSurfaces();for(auto& w:list.windows)baseline.insert(w.window);deadline=GetTickCount64()+5000;HMODULE module=nullptr;if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,(LPCWSTR)&PopupObserver::event,&module))throw std::runtime_error("Capture callback module unavailable");observer=this;show=SetWinEventHook(EVENT_OBJECT_SHOW,EVENT_OBJECT_SHOW,module,&PopupObserver::event,GetCurrentProcessId(),GetCurrentThreadId(),WINEVENT_INCONTEXT);menu=SetWinEventHook(EVENT_SYSTEM_MENUPOPUPSTART,EVENT_SYSTEM_MENUPOPUPSTART,module,&PopupObserver::event,GetCurrentProcessId(),GetCurrentThreadId(),WINEVENT_INCONTEXT);if(!show||!menu){cancel();throw std::runtime_error("Own popup observer unavailable");}}
 std::wstring state(){drive();bool ready=pending.state&&pending.succeeded();return std::wstring(L"{\"capture_ready\":")+(ready?L"true":L"false")+L",\"capture_expired\":"+((!deadline||GetTickCount64()>deadline)?L"true":L"false")+L"}";}
 Captured take(){if(!deadline)throw std::runtime_error("Capture not armed");drive();if(pending.state&&pending.succeeded()){Captured value=pending.take(pending.id);cancel();return value;}if(required){cancel();throw std::runtime_error("popup_not_observed");}cancel();return captureWindows();}
 ~PopupObserver(){cancel();}
};
class AgentHost final : public IComponentBase {
 IMemoryManager* memory=nullptr; INTERNET_PORT port=0; std::wstring token;PopupObserver popup;CaptureJob capture;
 static constexpr const wchar_t* methods[]={L"Configure",L"Poll",L"Complete",L"Hello",L"Stop",L"Screenshot",L"Capabilities",L"CaptureState",L"ArmScreenshot",L"CancelScreenshot",L"TakeArmedScreenshot",L"BeginScreenshot",L"PollScreenshot"};
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
 void ADDIN_API Done() override{capture.cancel();popup.cancel();port=0;token.clear();}
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
 long ADDIN_API GetNMethods() override{return 13;}
 long ADDIN_API FindMethod(const WCHAR_T* name) override{for(long i=0;i<13;i++)if(!_wcsicmp(name,methods[i]))return i;return -1;}
 const WCHAR_T* ADDIN_API GetMethodName(long i,long alias) override{
  trace("GetMethodName");if(i<0||i>=13||alias<0||alias>1||!memory)return nullptr;
  void* p=nullptr;size_t bytes=(wcslen(methods[i])+1)*sizeof(wchar_t);
  if(!memory->AllocMemory(&p,(unsigned long)bytes))return nullptr;memcpy(p,methods[i],bytes);return (WCHAR_T*)p;
 }
 long ADDIN_API GetNParams(long i) override{return i==0?2:(i==2||i==3||i==8||i==12?1:0);}
 bool ADDIN_API GetParamDefValue(long,long,tVariant*) override{return false;}
 bool ADDIN_API HasRetVal(long i) override{return i>=0&&i<13;}
 bool ADDIN_API CallAsProc(long i,tVariant* p,long n) override{tVariant v{};bool ok=CallAsFunc(i,&v,p,n);if(ok&&v.vt==VTYPE_PWSTR){void* ptr=v.pwstrVal;memory->FreeMemory(&ptr);}return ok;}
 bool ADDIN_API CallAsFunc(long i,tVariant* ret,tVariant* p,long n) override{
  try{
   if(i<0||i>=13||n!=GetNParams(i))return false;
   if(i==0){auto s=str(p[0]);size_t end=0;int value=std::stoi(s,&end);auto secret=str(p[1]);
    if(end!=s.size()||value<1024||value>65535||secret.size()!=64||secret.find_first_not_of(L"0123456789abcdef")!=std::wstring::npos)return false;
    result(ret,L"configured");if(port!=(INTERNET_PORT)value||token!=secret){capture.cancel();popup.cancel();}port=(INTERNET_PORT)value;token=secret;
   }else if(i==6){result(ret,L"{\"protocol\":2,\"features\":[\"screenshot_png\",\"own_window_composite_v1\",\"armed_popup_capture_v1\",\"async_window_print_v1\"]}");}
   else if(i==5||i==10){if(!port||token.empty())throw std::runtime_error("Not configured");if(i==5)popup.cancel();auto shot=i==10?popup.take():captureWindows();std::string meta="{\"scope\":\"own_ui_thread_composite\",\"window_count\":"+std::to_string(shot.windows)+",\"popup_count\":"+std::to_string(shot.popups)+",\"armed\":"+(shot.armed?"true":"false")+",\"renderer\":\"own_window_print_matte\",\"pixel_content_verified\":false,\"captured_at_ms\":"+std::to_string(shot.capturedAt)+",\"excluded_passive_surfaces\":"+std::to_string(shot.excludedPassive)+",\"capture_complete\":"+(shot.excludedPassive==0?"true":"false")+"}";result(ret,requestBytes(L"/snapshot",shot.png,true,meta));}
   else if(i==11){if(!port||token.empty())throw std::runtime_error("Not configured");popup.cancel();capture.begin();try{result(ret,L"{\"capture_id\":\""+capture.id+L"\",\"capture_pending\":true}");}catch(...){capture.cancel();throw;}}
   else if(i==12){auto id=str(p[0]);if(id!=capture.id)throw std::runtime_error("Capture identity changed");if(!capture.ready())result(ret,L"{\"capture_pending\":true}");else{auto shot=capture.take(id);std::string meta="{\"scope\":\"own_ui_thread_composite\",\"renderer\":\"own_window_print_matte\",\"pixel_content_verified\":false,\"window_count\":"+std::to_string(shot.windows)+",\"popup_count\":"+std::to_string(shot.popups)+",\"armed\":false,\"captured_at_ms\":"+std::to_string(shot.capturedAt)+",\"excluded_passive_surfaces\":"+std::to_string(shot.excludedPassive)+",\"capture_complete\":"+(shot.excludedPassive==0?"true":"false")+"}";result(ret,requestBytes(L"/snapshot",shot.png,true,meta));}}
   else if(i==7){result(ret,popup.state());}
   else if(i==8){auto mode=str(p[0]);if(mode!=L"popup"&&mode!=L"optional")throw std::runtime_error("Unsupported capture expectation");popup.arm(mode==L"popup");result(ret,L"armed");}
   else if(i==9){capture.cancel();popup.cancel();result(ret,L"cancelled");}
   else if(i==4){Done();result(ret,L"stopped");}
   else result(ret,requestBytes(i==1?L"/poll":i==2?L"/result":L"/hello",utf8(i==1?L"{}":str(p[0]))));
   return true;
  }catch(const std::exception& failure){trace(failure.what());return false;}catch(...){trace("Unknown native call failure");return false;}
 }
 void ADDIN_API SetLocale(const WCHAR_T*) override{}
};
}
extern "C" long GetClassObject(const WCHAR_T* name,IComponentBase** out){trace("GetClassObject");try{if(!out||*out||wcscmp(name,L"AgentHost"))return 0;*out=new AgentHost();return 1;}catch(...){return 0;}}
extern "C" long DestroyObject(IComponentBase** out){if(!out||!*out)return -1;delete *out;*out=nullptr;return 0;}
extern "C" const WCHAR_T* GetClassNames(){trace("GetClassNames");return L"AgentHost";}
extern "C" AppCapabilities SetPlatformCapabilities(AppCapabilities){trace("SetPlatformCapabilities");return eAppCapabilities1;}
