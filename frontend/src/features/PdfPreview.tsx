import { beginRequest } from "../requestProgress";
import { memo, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import {
  GlobalWorkerOptions,
  type PDFDocumentProxy,
  type RenderTask,
} from "pdfjs-dist";
import worker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { FileProgress, type Transfer } from "../components/FileProgress";
import { PreviewCache } from "./PreviewCache";
import { ErrorText } from "../components/ui";
GlobalWorkerOptions.workerSrc = worker;
function PdfCanvas({
  pdf,
  page,
  width,
  thumbnail = false,
  onRendered,
}: {
  pdf: PDFDocumentProxy;
  page: number;
  width: number;
  thumbnail?: boolean;
  onRendered?: (image: string, text: string) => void;
}) {
  const mount = useRef<HTMLDivElement>(null),
    [error, setError] = useState(""),
    [visible, setVisible] = useState(!thumbnail);
  useEffect(() => {
    if (!thumbnail) return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {setVisible(true);observer.disconnect();}
    }, { rootMargin: "120px" });
    observer.observe(mount.current!);
    return () => observer.disconnect();
  }, [thumbnail]);
  useEffect(() => {
    if (!width || !visible) return;
    let active = true,
      render: RenderTask | undefined;
    const canvas = document.createElement("canvas");
    canvas.setAttribute(
      "aria-label",
      (thumbnail ? "缩略图 " : "第 ") + page + " 页",
    );
    pdf
      .getPage(page)
      .then((p) => {
        if (!active) return;
        const original = p.getViewport({ scale: 1 }),
          scale = width / original.width,
          ratio = thumbnail ? 1 : Math.min(window.devicePixelRatio, 2),
          viewport = p.getViewport({ scale: scale * ratio });
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        canvas.style.width = "100%";
        canvas.style.height = "auto";
        render = p.render({ canvas, viewport });
        return render.promise.then(async () => {
          if (!active) return;
          mount.current!.replaceChildren(canvas);
          if (onRendered) {
            const content = await p.getTextContent();
            if (active) onRendered(canvas.toDataURL("image/jpeg", .9), content.items.map(item => "str" in item ? item.str + (item.hasEOL ? "\n" : " ") : "").join(""));
          }
        });
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
      render?.cancel();
    };
  }, [pdf, page, width, thumbnail, visible, onRendered]);
  return (
    <>
      <div
        ref={mount}
        className={thumbnail ? "pdf-thumbnail-canvas" : "pdf-page-canvas"}
      />
      <ErrorText error={error} />
    </>
  );
}
const ContinuousPage = memo(function ContinuousPage({ pdf, index, number, width, paged, onRendered }: {
  pdf?: PDFDocumentProxy; index: number; number: number; width: number; paged: boolean;
  onRendered: (number: number, image: string, text: string) => void;
}) {
  const element = useRef<HTMLElement>(null);
  const [visible, setVisible] = useState(false), [renderWidth, setRenderWidth] = useState(0), [ratio, setRatio] = useState(paged ? 9 / 16 : 1.414);
  const measured = width > 0;
  useEffect(() => {
    if (!measured) return;
    const observer = new IntersectionObserver(entries => {
      setVisible(entries.some(entry => entry.isIntersecting));
    }, { root: element.current!.parentElement, rootMargin: "600px" });
    observer.observe(element.current!);
    return () => observer.disconnect();
  }, [measured]);
  useEffect(() => {
    if (visible && width) setRenderWidth(width);
  }, [visible, width]);
  useEffect(() => {
    let active = true;
    if (pdf && visible) pdf.getPage(index).then(page => {const size=page.getViewport({scale:1});if(active)setRatio(size.height/size.width);});
    return () => {active=false;};
  }, [pdf,index,visible]);
  const rendered = useCallback((image: string, text: string) => onRendered(number,image,text),[number,onRendered]);
  return <section ref={element} className="continuous-page" data-page-number={number} aria-label={"第 " + number + " 页"} style={{aspectRatio:1/ratio}}>
    {pdf && renderWidth > 0 ? <PdfCanvas pdf={pdf} page={index} width={renderWidth} onRendered={rendered} /> : !pdf && <div className="slide-waiting">第 {number} 页正在加载</div>}
  </section>;
});

export function PdfPreview({url,translation,onPage,preparingLabel="加载预览",pageCount,availablePages,thumbnailUrl,onLoadedPages}: {
  url:string;translation?:ReactNode;preparingLabel?:string;pageCount?:number;availablePages?:number[];thumbnailUrl?:string;
  onLoadedPages?:(count:number)=>void;onPage:(page:number,text:string,image:string)=>void;
}) {
  const [pdf,setPdf]=useState<PDFDocumentProxy>(),[page,setPage]=useState(1),[width,setWidth]=useState(0),
    [error,setError]=useState(""),[progress,setProgress]=useState<Transfer>({loaded:0,total:0}),[,refresh]=useState(0);
  const cache=useRef<PreviewCache|null>(null),scroll=useRef<HTMLDivElement>(null),current=useRef(1),
    captures=useRef(new Map<number,{image:string;text:string}>()),
    center=useRef<{number:number;position:number}|undefined>(undefined),resizing=useRef(false),layoutWidth=useRef(0);
  function rememberCenter(){
    if(resizing.current)return;
    const view=scroll.current!;
    if(view.clientWidth!==layoutWidth.current)return;
    const line=view.getBoundingClientRect().top+view.clientHeight/2;
    for(const element of view.querySelectorAll<HTMLElement>("[data-page-number]")){
      const rect=element.getBoundingClientRect();
      if(rect.bottom>=line){center.current={number:Number(element.dataset.pageNumber),position:(line-rect.top)/rect.height};break;}
    }
  }
  function restoreCenter(){
    if(!center.current)return;
    const view=scroll.current!,anchor=center.current;
    const target=view.querySelector<HTMLElement>(`[data-page-number="${anchor.number}"]`);
    if(!target)return;
    const rect=target.getBoundingClientRect();
    view.scrollTop+=rect.top+rect.height*anchor.position-view.getBoundingClientRect().top-view.clientHeight/2;
  }
  useEffect(()=>{
    const documents=new PreviewCache(count=>{refresh(value=>value+1);onLoadedPages?.(count);});cache.current=documents;
    return ()=>{void documents.destroy();};
  },[url,onLoadedPages]);
  useEffect(()=>{
    let active=true;
    const finish=beginRequest();
    async function load(){
      if(pageCount){
        for(const number of availablePages || []){
          if(!active)return;
          await cache.current!.load(url+"?page="+number);
        }
      }else{
        const document=await cache.current!.load(url,value=>{if(active)setProgress(value);});
        if(active)setPdf(document);
      }
    }
    load().catch(error=>{if(active)setError(error.message);}).finally(finish);
    return ()=>{active=false;};
  },[url,pageCount,availablePages]);
  useEffect(()=>{
    let timer:number|undefined, frame:number|undefined, initialized=false, observedWidth=0;
    const observer=new ResizeObserver(([entry])=>{
      const next=Math.round(entry.contentRect.width);
      // Translation panel resizing changes height only; keep the document at its scroll position.
      if(next===observedWidth)return;
      observedWidth=next;
      window.clearTimeout(timer);
      if(!initialized&&next>0){initialized=true;layoutWidth.current=scroll.current!.clientWidth;setWidth(next);}
      else {
        resizing.current=true;
        scroll.current!.style.overflowAnchor="none";
        restoreCenter();
        timer=window.setTimeout(()=>{
          setWidth(next);
          frame=requestAnimationFrame(()=>{
            restoreCenter();layoutWidth.current=scroll.current!.clientWidth;
            resizing.current=false;scroll.current!.style.overflowAnchor="";rememberCenter();updateCurrent();
          });
        },120);
      }
    });observer.observe(scroll.current!);
    scroll.current!.focus({preventScroll:true});return ()=>{observer.disconnect();window.clearTimeout(timer);if(frame!==undefined)cancelAnimationFrame(frame);};
  },[]);
  const select=useCallback((number:number)=>{
    if(current.current===number)return;
    current.current=number;setPage(number);
    const capture=captures.current.get(number);onPage(number,capture?.text || "",capture?.image || "");
  },[onPage]);
  const rendered=useCallback((number:number,image:string,text:string)=>{
    rememberCenter();
    captures.current.set(number,{image,text});if(current.current===number)onPage(number,text,image);
  },[onPage]);
  const total=pageCount || pdf?.numPages || 0;
  useEffect(()=>{rememberCenter();},[total,width]);
  const go=useCallback((number:number)=>{
    if(number<1 || number>total || (pageCount && !availablePages?.includes(number)))return;
    const view=scroll.current!,target=view.querySelector<HTMLElement>(`[data-page-number="${number}"]`)!;
    view.scrollTo({top:view.scrollTop+target.getBoundingClientRect().top-view.getBoundingClientRect().top-26,behavior:"instant"});select(number);
  },[total,pageCount,availablePages,select]);
  useEffect(()=>{
    const navigate=(event:KeyboardEvent)=>{
      if(event.altKey||event.ctrlKey||event.metaKey||event.isComposing||!["ArrowLeft","ArrowRight"].includes(event.key))return;
      event.preventDefault();event.stopPropagation();go(current.current+(event.key==="ArrowRight"?1:-1));
    };
    window.addEventListener("keydown",navigate,true);return ()=>window.removeEventListener("keydown",navigate,true);
  },[go]);
  function updateCurrent(){
    if(resizing.current)return;
    rememberCenter();
    const view=scroll.current!,line=view.getBoundingClientRect().top+Math.min(view.clientHeight*.35,200);
    for(const element of view.querySelectorAll<HTMLElement>("[data-page-number]")){
      const rect=element.getBoundingClientRect();if(rect.bottom>line){select(Number(element.dataset.pageNumber));break;}
    }
  }
  return <div className="reader-pdf">
    <aside className="reader-thumbnails" aria-label="文档页目录">
      {Array.from({length:total},(_,i)=><button key={i} className={"reader-thumbnail "+(page===i+1?"selected":"")} onClick={()=>go(i+1)} disabled={!!pageCount&&!availablePages?.includes(i+1)} aria-label={"第 "+(i+1)+" 页"} aria-current={page===i+1?"page":undefined}>
        {pageCount ? availablePages?.includes(i+1)&&<img src={thumbnailUrl+"?page="+(i+1)} width={86} loading="lazy" alt=""/> : pdf&&<PdfCanvas pdf={pdf} page={i+1} width={86} thumbnail/>}<span>{i+1}</span>
      </button>)}
    </aside>
    <div className="reader-pdf-column">
      <div className="reader-scroll reader-pdf-scroll" ref={scroll} tabIndex={0} aria-label={"文档第 "+page+" 页"} onScroll={updateCurrent} onPointerDown={event=>event.currentTarget.focus({preventScroll:true})}
        onKeyDown={event=>{if(event.altKey||event.ctrlKey||event.metaKey||!["ArrowUp","ArrowDown"].includes(event.key))return;event.preventDefault();event.stopPropagation();event.currentTarget.scrollBy(0,event.key==="ArrowDown"?60:-60);}}>
        <ErrorText error={error}/>
        {!total&&!error&&<FileProgress progress={progress} label={progress.total>0?"加载预览":preparingLabel}/>}
        {Array.from({length:total},(_,i)=><ContinuousPage key={i} pdf={pageCount?cache.current?.peek(url+"?page="+(i+1)):pdf} index={pageCount?1:i+1} number={i+1} width={width} paged={!!pageCount} onRendered={rendered}/>)}
      </div>
      {translation}
    </div>
  </div>;
}
