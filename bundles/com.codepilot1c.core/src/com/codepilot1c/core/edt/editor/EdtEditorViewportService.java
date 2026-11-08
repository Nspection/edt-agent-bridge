package com.codepilot1c.core.edt.editor;

import java.util.*;
import java.util.List;
import java.util.zip.CRC32;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;

/** Addresses view controls explicitly; does not edit the form model. */
final class EdtEditorViewportService {
    private record Region(String id,String role,Control control,Object representation){}
    static Map<String,Object> execute(String action,IWorkbenchPage page,IEditorPart editor,Map<String,Object> args) throws Exception {
        if(action.equals("viewport")) {
            String mode=option(args,"mode","state",Set.of("state","maximize","restore"));
            IWorkbenchPartReference reference=page.getReference(editor);
            int before=page.getPartState(reference);
            if(mode.equals("maximize")){page.setPartState(reference,IWorkbenchPage.STATE_MAXIMIZED);}
            if(mode.equals("restore")){page.setPartState(reference,IWorkbenchPage.STATE_RESTORED);}
            editor.getSite().getShell().layout(true,true);
            int after=page.getPartState(reference);
            return Map.of("mode",mode,"before",partState(before),"after",partState(after),"changed",before!=after);
        }
        if(action.equals("form_preview")){return formPreview(editor,args);}
        List<Region> regions=regions(editor);
        if(action.equals("regions")) {
            List<Map<String,Object>> descriptions=new ArrayList<>();
            for(Region region:regions){descriptions.add(describe(region));}
            return Map.of("regions",descriptions,"count",descriptions.size());
        }
        Object target=args.get("target");
        Region region=regions.stream().filter(r->r.id.equals(target)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown or hidden target; call edt_editor_regions for the active page"));
        String axis=option(args,"axis","vertical",Set.of("vertical","horizontal"));
        String operation=option(args,"operation","by",Set.of("by","page","start","end"));
        int amount=integer(args,"amount",region.role.equals("preview_content")?3:300);
        Map<String,Object> before=describe(region);
        Map<String,Object> details=new LinkedHashMap<>();
        if(region.role.equals("preview_content")) {
            if(!axis.equals("vertical") || !operation.equals("by")){throw new IllegalArgumentException("preview_content supports vertical by with amount in wheel notches; use preview for outer pixel scrolling");}
            if(Math.abs((long)amount)>30){throw new IllegalArgumentException("Native preview amount must be between -30 and 30 wheel notches");}
            long oldImage=imageHash(region.representation);
            Control control=region.control;
            Rectangle visible=visibleBounds(control);
            if(visible.width<1 || visible.height<1){throw new IllegalStateException("Preview has no visible viewport");}
            Event event=new Event();event.widget=control;event.display=control.getDisplay();
            event.x=visible.x+visible.width/2;event.y=visible.y+visible.height/2;
            event.count=-amount;event.type=SWT.MouseWheel;
            Object handler=EdtEditorService.call(control,"getHandler",new Class<?>[0]);
            // Invoke the preview's own wheel handler; no desktop input or cursor movement.
            EdtEditorService.call(handler,"mouseScrolled",new Class<?>[]{MouseEvent.class},new MouseEvent(event));
            long newImage=imageHash(region.representation);
            details.put("units","wheel_notches");details.put("requested",amount);
            details.put("image_changed",oldImage!=-1 && newImage!=-1 ? oldImage!=newImage : null);
            details.put("end_reached",null); // The native renderer exposes no authoritative range here.
        } else {
            scroll(region.control,axis,operation,amount);
            details.put("units",region.control instanceof Table || region.control instanceof Tree ? "pixel_delta_converted_to_rows" : "pixels");
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("target",region.id);result.put("axis",axis);result.put("operation",operation);
        result.put("before",before);result.put("after",describe(region));result.put("details",details);
        return result;
    }
    private static Map<String,Object> formPreview(IEditorPart editor,Map<String,Object> args) throws Exception {
        Object pages=EdtEditorService.field(editor,"pages"),viewer=null,representation=null;
        if(pages instanceof Iterable<?> list){for(Object page:list){if("editors.form.pages.main".equals(EdtEditorService.optionalCall(page,"getId",new Class<?>[0]))){viewer=EdtEditorService.field(page,"wysiwygViewer");if(viewer!=null){representation=EdtEditorService.field(viewer,"wysiwygRepresentation");}break;}}}
        if(representation==null){throw new IllegalStateException("Open the Form page of a managed-form editor first");}
        Object value=EdtEditorService.field(representation,"form");if(!(value instanceof org.eclipse.emf.ecore.EObject form)){throw new IllegalStateException("Native form projection is not ready; retry later");}
        var elements=new ArrayList<org.eclipse.emf.ecore.EObject>();elements.add(form);var iterator=form.eAllContents();while(iterator.hasNext()){var item=iterator.next();if(item.eClass().getEStructuralFeature("name")!=null && item.eClass().getEStructuralFeature("id")!=null){elements.add(item);}if(elements.size()>10000){throw new IllegalStateException("Form element limit exceeded");}}
        String mode=option(args,"mode","list",Set.of("list","select"));
        if(mode.equals("select")){
            Object id=args.get("element_id"),name=args.get("element_name");
            if((id==null)==(name==null)){throw new IllegalArgumentException("Provide exactly one element_id or element_name");}
            var matches=elements.stream().filter(e->id!=null?String.valueOf(property(e,"id")).equals(id instanceof Number n?String.valueOf(n.intValue()):String.valueOf(id)):Objects.equals(name,property(e,"name"))).toList();
            if(matches.size()!=1){throw new IllegalArgumentException("Expected one form element; found "+matches.size());}
            EdtEditorService.call(viewer,"setSelection",new Class<?>[]{org.eclipse.jface.viewers.ISelection.class,boolean.class},new org.eclipse.jface.viewers.StructuredSelection(matches.get(0)),true);
        }
        var selection=EdtEditorService.optionalCall(viewer,"getSelection",new Class<?>[0]);boolean domainFallback=false;if(selection instanceof org.eclipse.jface.viewers.IStructuredSelection selectedValue && selectedValue.isEmpty()){selection=EdtEditorService.field(representation,"lastDomainSelection");domainFallback=true;}var selected=new ArrayList<Map<String,Object>>();if(selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured){for(Object e:structured.toArray()){if(e instanceof org.eclipse.emf.ecore.EObject object){selected.add(element(object));}}}
        int offset=integer(args,"offset",0),limit=integer(args,"limit",100);if(offset<0 || limit<1 || limit>500){throw new IllegalArgumentException("offset>=0 and limit 1..500 required");}
        List<Map<String,Object>> rows=elements.stream().map(EdtEditorViewportService::element).toList();
        Map<String,Object> result=new LinkedHashMap<>();result.put("mode",mode);result.put("selected",selected);result.put("selection_source",domainFallback?"native_last_domain_selection":"native_current_selection");result.put("elements",rows.stream().skip(offset).limit(limit).toList());result.put("total",rows.size());result.put("has_more",offset+limit<rows.size());result.put("pages",rows.stream().filter(e->String.valueOf(e.get("group_type")).equalsIgnoreCase("Page")).toList());
        Object image=EdtEditorService.field(representation,"formImageData");if(image instanceof ImageData data && data.width>0 && data.height>0){result.put("render_state","image_available");result.put("preview_size",Map.of("width",data.width,"height",data.height));result.put("image_crc32",imageHash(representation));}else{Object nativeControl=EdtEditorService.field(representation,"formNativeComposite");result.put("render_state",nativeControl instanceof Control c && !c.isDisposed() && c.isVisible()?"native_renderer_capture_required":"pending");}
        result.put("note","Native selection reveals containing pages; image availability does not certify layout correctness. Capture and inspect the screenshot.");return result;
    }
    private static Object property(org.eclipse.emf.ecore.EObject object,String name){var feature=object.eClass().getEStructuralFeature(name);return feature==null?null:object.eGet(feature);}
    private static Map<String,Object> element(org.eclipse.emf.ecore.EObject object){var row=new LinkedHashMap<String,Object>();row.put("name",property(object,"name"));row.put("id",property(object,"id"));row.put("kind",object.eClass().getName());row.put("group_type",property(object,"type"));if(object.eContainer()!=null){row.put("parent",property(object.eContainer(),"name"));}return row;}
    private static String partState(int value){return value==IWorkbenchPage.STATE_MAXIMIZED?"maximized":value==IWorkbenchPage.STATE_MINIMIZED?"minimized":"restored";}
    private static List<Region> regions(IEditorPart editor) throws Exception {
        List<Region> result=new ArrayList<>();
        Control root=(Control)EdtEditorService.call(editor,"getContainer",new Class<?>[0]);
        Object pages=EdtEditorService.field(editor,"pages");
        if(pages instanceof Iterable<?> iterable) {
            for(Object formPage:iterable) {
                Object id=EdtEditorService.optionalCall(formPage,"getId",new Class<?>[0]);
                if(!"editors.form.pages.main".equals(id)){continue;}
                Object viewer=EdtEditorService.field(formPage,"wysiwygViewer");
                Object representation=viewer==null?null:EdtEditorService.field(viewer,"wysiwygRepresentation");
                if(representation!=null) {
                    Object outer=EdtEditorService.field(representation,"scrolledComposite");
                    Object nativeControl=EdtEditorService.field(representation,"formNativeComposite");
                    if(outer instanceof Control c && !c.isDisposed() && c.isVisible()){result.add(new Region("preview","preview",c,representation));}
                    if(nativeControl instanceof Control c && !c.isDisposed() && c.isVisible()){result.add(new Region("preview_content","preview_content",c,representation));}
                }
            }
        }
        collect(root,result);
        return result;
    }
    private static void collect(Control control,List<Region> result) {
        if(control.isDisposed() || !control.isVisible()){return;}
        if(result.size()>=200){throw new IllegalStateException("Too many scroll regions in the active editor");}
        if(control instanceof Scrollable s && (s.getVerticalBar()!=null || s.getHorizontalBar()!=null)
                && result.stream().noneMatch(r->r.control==control)) {
            result.add(new Region("region@"+Integer.toHexString(System.identityHashCode(control)),control.getClass().getSimpleName(),control,null));
        }
        if(control instanceof Composite composite){for(Control child:composite.getChildren()){collect(child,result);}}
    }
    private static Map<String,Object> describe(Region region) throws Exception {
        Control control=region.control;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",region.id);out.put("role",region.role);out.put("type",control.getClass().getName());
        Point size=control.getSize();out.put("width",size.x);out.put("height",size.y);
        if(control instanceof Scrollable s){out.put("vertical",bar(s.getVerticalBar()));out.put("horizontal",bar(s.getHorizontalBar()));}
        if(control instanceof ScrolledComposite sc){Point origin=sc.getOrigin();out.put("origin",Map.of("x",origin.x,"y",origin.y));}
        if(control instanceof Table table){out.put("top_index",table.getTopIndex());out.put("item_count",table.getItemCount());}
        if(control instanceof Tree tree){TreeItem item=tree.getTopItem();out.put("top_item",item==null?null:item.getText());}
        if(control instanceof StyledText text){out.put("top_pixel",text.getTopPixel());out.put("horizontal_pixel",text.getHorizontalPixel());}
        return out;
    }
    private static Map<String,Object> bar(ScrollBar bar) {
        if(bar==null){return null;}
        int lower=bar.getMinimum(),upper=Math.max(lower,bar.getMaximum()-bar.getThumb()),position=bar.getSelection();
        return Map.of("minimum",lower,"maximum",upper,"selection",position,"page",bar.getPageIncrement(),
                "can_scroll",bar.isEnabled() && upper>lower,"at_start",position<=lower,"at_end",position>=upper);
    }
    private static void scroll(Control control,String axis,String operation,int amount) {
        boolean vertical=axis.equals("vertical");
        if(!(control instanceof Scrollable s)){throw new IllegalArgumentException("Target has no SWT scrolling API");}
        ScrollBar bar=vertical?s.getVerticalBar():s.getHorizontalBar();
        if(bar==null){throw new IllegalArgumentException("Target has no "+axis+" scroll bar");}
        if(control instanceof Table table && vertical) {
            int rows=rowDelta(amount,table.getItemHeight());
            int page=Math.max(1,table.getClientArea().height/Math.max(1,table.getItemHeight()));
            table.setTopIndex(destination(table.getTopIndex(),0,Math.max(0,table.getItemCount()-1),page,operation,rows));return;
        }
        if(control instanceof Tree tree && vertical) {
            List<TreeItem> visible=new ArrayList<>();for(TreeItem item:tree.getItems()){collectItems(item,visible);}
            if(visible.isEmpty()){return;}
            int current=Math.max(0,visible.indexOf(tree.getTopItem()));
            int page=Math.max(1,tree.getClientArea().height/Math.max(1,tree.getItemHeight()));
            tree.setTopItem(visible.get(destination(current,0,visible.size()-1,page,operation,rowDelta(amount,tree.getItemHeight()))));return;
        }
        int selection=destination(bar.getSelection(),bar.getMinimum(),Math.max(bar.getMinimum(),bar.getMaximum()-bar.getThumb()),bar.getPageIncrement(),operation,amount);
        if(control instanceof ScrolledComposite sc) {
            Point origin=sc.getOrigin();sc.setOrigin(vertical?origin.x:selection,vertical?selection:origin.y);
        } else if(control instanceof StyledText text) {
            if(vertical){text.setTopPixel(selection);}else{text.setHorizontalPixel(selection);}
        } else {
            bar.setSelection(selection);Event event=new Event();event.widget=bar;event.detail=SWT.NONE;bar.notifyListeners(SWT.Selection,event);
        }
        control.update();
    }
    private static void collectItems(TreeItem item,List<TreeItem> visible){visible.add(item);if(item.getExpanded()){for(TreeItem child:item.getItems()){collectItems(child,visible);}}}
    static int rowDelta(int pixels,int rowHeight){return pixels==0?0:(int)Math.copySign(Math.max(1,Math.abs((long)pixels)/Math.max(1,rowHeight)),pixels);}
    static int destination(int current,int min,int max,int page,String operation,int amount) {
        long candidate=switch(operation){case "start"->min;case "end"->max;case "by"->(long)current+amount;case "page"->(long)current+(long)Math.max(1,page)*amount;default->throw new IllegalArgumentException("Unknown scroll operation");};
        return (int)Math.max(min,Math.min(max,candidate));
    }
    private static Rectangle visibleBounds(Control control){
        Point start=control.toDisplay(0,0);Rectangle visible=new Rectangle(start.x,start.y,control.getSize().x,control.getSize().y);
        for(Composite parent=control.getParent();parent!=null;parent=parent.getParent()){
            Rectangle client=parent.getClientArea();Point origin=parent.toDisplay(client.x,client.y);
            visible=visible.intersection(new Rectangle(origin.x,origin.y,client.width,client.height));
        }
        return new Rectangle(visible.x-start.x,visible.y-start.y,visible.width,visible.height);
    }
    private static long imageHash(Object representation) throws Exception {
        Object image=EdtEditorService.field(representation,"formImageData");
        if(!(image instanceof ImageData data)){return -1;}
        CRC32 crc=new CRC32();crc.update(data.data);return crc.getValue();
    }
    private static String option(Map<String,Object> args,String name,String fallback,Set<String> accepted){
        Object value=args.getOrDefault(name,fallback);if(!(value instanceof String text) || !accepted.contains(text)){throw new IllegalArgumentException("Invalid "+name+"; accepted: "+accepted);}return (String)value;
    }
    private static int integer(Map<String,Object> args,String name,int fallback){
        Object value=args.getOrDefault(name,fallback);if(!(value instanceof Number n) || n.doubleValue()!=n.intValue() || Math.abs((long)n.intValue())>10000){throw new IllegalArgumentException(name+" must be an integer between -10000 and 10000");}return ((Number)value).intValue();
    }
}
