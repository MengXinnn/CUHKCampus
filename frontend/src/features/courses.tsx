import "./courses.css";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Download,
  Folder,
  Archive,
  FileText,
  GraduationCap,
  Files,
  CalendarDays,
  Sparkles,
  Plus,
  ArrowUpRight,
  Pencil,
  ChevronRight,
  ChevronLeft,
} from "lucide-react";
import { api, downloadDocument, downloadDocumentSelection, params, date, type Item, type DownloadProgress } from "../api";
import {
  Empty,
  ErrorText,
  useData,
  Modal,
  Field,
  SearchBox,
  PageHeader,
  FormModal,
} from "../components/ui";
function courseCode(course:Item) { return String(course.code||course.name||"").match(/[A-Z]{3,4}\s?\d{4}/)?.[0].replace(/\s/g,"") || course.code; }
function courseTitle(course:Item) { return course.customName || String(course.name||"").replace(/^[A-Z]{3,4}\s?\d{4}\s*:\s*/,"").replace(/_L\d.*$/,"").replace(/_/g," "); }
function courseTone(course:Item) { return [...String(courseCode(course))].reduce((n,c)=>n+c.charCodeAt(0),0)%4; }
export function Courses() {
  const [q, setQ] = useState(""),
    [create, setCreate] = useState(false),
    [term, setTerm] = useState(""),
    [archived, setArchived] = useState(false);
  const data = useData("/courses");
  const courses=data.data||[];
  const terms=[...new Set(courses.map(c=>c.term).filter(Boolean))].sort().reverse();
  const visible=courses.filter(c=>(!term||c.term===term)&&(archived||!c.archived)&&(!q||`${c.code||""} ${c.customName||c.name}`.toLowerCase().includes(q.toLowerCase())));
  return (
    <section className="courses-page">
      <PageHeader title="课程中心">
        <button className="secondary" onClick={()=>setCreate(true)}><Plus size={16}/>添加课程</button>
      </PageHeader>
      <div className="courses-toolbar">
        <div className="courses-term"><GraduationCap size={18}/><select aria-label="筛选学期" value={term} onChange={e=>setTerm(e.target.value)}><option value="">全部学期</option>{terms.map(value=><option key={value}>{value}</option>)}</select></div>
        <div className="courses-toolbar-actions"><SearchBox value={q} onChange={setQ} placeholder="搜索课程"/><button className={"icon courses-archive"+(archived?" active":"")} aria-label="显示归档课程" aria-pressed={archived} title="显示归档课程" onClick={()=>setArchived(!archived)}><Archive size={17}/></button></div>
      </div>
      <ErrorText error={data.error}/>
      <div className="courses-collection">
        {visible.map((course,index)=><Link key={course.id} to={"/courses/"+course.id} className={"course-tile course-palette-"+courseTone(course)} style={{animationDelay:`${Math.min(index,8)*35}ms`}}>
          <div className="course-tile-top"><span>{courseCode(course)}</span><span className="course-tile-term">{course.term}</span></div>
          <div className="course-tile-mark" aria-hidden="true"><span/><span/><span/></div>
          <h2 title={course.customName||course.name}>{courseTitle(course)}</h2>
          <div className="course-tile-bottom"><span>{course.teacher}</span><span className="course-tile-arrow"><ArrowUpRight size={19}/></span></div>
        </Link>)}
      </div>
      {data.isSuccess&&!visible.length&&<Empty>{q||term?"没有匹配的课程":"暂无课程"}</Empty>}
      {create && (
        <FormModal
          title="添加课程"
          close={() => setCreate(false)}
          submit={(d) => api("/courses", "POST", d)}
        >
          <Field label="课程代码">
            <input name="code" required />
          </Field>
          <Field label="课程名称">
            <input name="name" required />
          </Field>
          <Field label="学期">
            <input name="term" required />
          </Field>
          <Field label="教师">
            <input name="teacher" />
          </Field>
        </FormModal>
      )}
    </section>
  );
}
export function CourseDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const course = useData<Item>("/courses/" + id),
    ann = useData("/announcements?courseId=" + id),
    notes = useData("/courses/" + id + "/notes"),
    docs = useData("/documents?courseId=" + id);
  const [tab,setTab]=useState("content"),
    [edit, setEdit] = useState(false),
    [note, setNote] = useState<Item | null | undefined>(),
    [node, setNode] = useState<Item | null>(null);
  return (
    <div className="course-detail">
      <Link to="/courses" className="course-breadcrumb"><ChevronLeft size={15}/>课程中心</Link>
      <header className={"course-hero course-palette-"+(course.data?courseTone(course.data):0)}>
        <div className="course-hero-copy">
          <div className="course-hero-meta"><span>{course.data&&courseCode(course.data)}</span><span>{course.data?.term}</span></div>
          <h1>{course.data?courseTitle(course.data):"课程"}</h1>
          {course.data?.teacher&&<p>{course.data.teacher}</p>}
        </div>
        <div className="course-hero-actions"><button className="icon" aria-label="编辑课程" title="编辑课程" onClick={()=>setEdit(true)}><Pencil size={17}/></button><Link className="primary" to={"/copilot?courseId="+id}><Sparkles size={16}/>询问 Copilot</Link></div>
      </header>
      <ErrorText error={course.error||ann.error||docs.error||notes.error}/>
      <div className="course-workspace">
        <section className="course-materials">
          <div className="course-tabs" role="tablist" aria-label="课程资料">
            {[["content","课程内容"],["documents","文档"],["grades","成绩"]].map(([value,label])=><button key={value} role="tab" id={"course-tab-"+value} aria-controls="course-panel" aria-selected={tab===value} onClick={()=>setTab(value)}>{label}{value==="documents"&&!!docs.data?.length&&<span>{docs.data.length}</span>}</button>)}
          </div>
          <div className="course-material-panel" id="course-panel" role="tabpanel" aria-labelledby={"course-tab-"+tab} key={tab}>
            {tab==="content"&&<ContentBrowser courseId={id!} onSelect={setNode}/>}
            {tab==="documents"&&<div className="course-document-grid">{docs.data?.map(d=><Link className="course-document" key={d.id} to={"/documents/"+d.id} title={d.displayName}><span className="course-document-icon"><FileText size={25}/></span><span><strong>{d.displayName}</strong><small>{d.displayName.split(".").pop()?.toUpperCase()}</small></span><ArrowUpRight size={16}/></Link>)}{docs.data?.length===0&&<Empty>暂无文档</Empty>}</div>}
            {tab==="grades"&&<Gradebook courseId={id!}/>}
          </div>
        </section>
        <aside className="course-aside">
          <section className="course-aside-card"><div className="course-section-heading"><h2>公告</h2></div>
            <div className="course-announcements">{ann.data?.map(a=><details className="course-announcement" key={a.id} onToggle={e=>{if(e.currentTarget.open&&!a.isRead)void api("/announcements/"+a.id,"PATCH",{isRead:true});}}><summary><span>{!a.isRead&&<i/>}{a.title}</span><ChevronRight size={14}/></summary><time>{date(a.publishedAt)}</time><p>{a.bodyText}</p></details>)}{ann.data?.length===0&&<Empty>暂无公告</Empty>}</div>
          </section>
          {course.data&&<CourseSchedule code={courseCode(course.data)}/>}
          <section className="course-aside-card"><div className="course-section-heading"><h2>笔记</h2><button className="icon" aria-label="新建笔记" onClick={()=>setNote(null)}><Plus size={18}/></button></div><div className="course-notes">{notes.data?.map(n=><button key={n.id} onClick={()=>setNote(n)}><FileText size={16}/><span>{n.title}</span><Pencil size={13}/></button>)}{notes.data?.length===0&&<button className="course-add-note" onClick={()=>setNote(null)}><Plus size={16}/>写一篇笔记</button>}</div></section>
        </aside>
      </div>
      {edit && course.data && (
        <FormModal
          title="编辑课程"
          close={() => setEdit(false)}
          submit={(d) =>
            api("/courses/" + id, "PATCH", {
              ...d,
              archived: d.archived === "on",
            })
          }
        >
          <Field label="显示名称">
            <input
              name="customName"
              defaultValue={course.data.customName || course.data.name}
            />
          </Field>
          <Field label="学期">
            <input name="term" defaultValue={course.data.term} />
          </Field>
          <Field label="颜色">
            <input
              type="color"
              name="color"
              defaultValue={course.data.color || "#D6E0CF"}
            />
          </Field>
          <Field label="备注">
            <textarea name="note" defaultValue={course.data.note} />
          </Field>
          <label className="checkbox-line">
            <input
              name="archived"
              type="checkbox"
              defaultChecked={course.data.archived}
            />
            归档课程
          </label>
        </FormModal>
      )}
      {note !== undefined && (
        <FormModal
          title={note ? "编辑笔记" : "新建笔记"}
          close={() => setNote(undefined)}
          submit={(d) =>
            api(
              note ? "/notes/" + note.id : "/courses/" + id + "/notes",
              note ? "PATCH" : "POST",
              d,
            )
          }
        >
          <Field label="标题">
            <input name="title" defaultValue={note?.title} required />
          </Field>
          <Field label="笔记">
            <textarea rows={12} name="markdown" defaultValue={note?.markdown} />
          </Field>
          {note && (
            <button
              className="text-button danger"
              type="button"
              onClick={async () => {
                await api("/notes/" + note.id, "DELETE");
                setNote(undefined);
                qc.invalidateQueries();
              }}
            >
              删除笔记
            </button>
          )}
        </FormModal>
      )}
      {node && (
        <ContentDetail id={node.id} close={() => setNode(null)} />
      )}
    </div>
  );
}
function Gradebook({courseId}:{courseId:string}) {
  const grades=useQuery<Item[]>({queryKey:["/courses/"+courseId+"/grades"],queryFn:()=>api("/courses/"+courseId+"/grades")});
  return <div className="course-gradebook"><ErrorText error={grades.error}/>{grades.data?.length===0&&<Empty>暂无成绩项目</Empty>}{!!grades.data?.length&&<table><thead><tr><th>项目</th><th>成绩</th><th>满分</th></tr></thead><tbody>{grades.data.map(column=><tr key={column.id}><td>{column.name}</td><td>{column.grade?.exempt?"已豁免":column.grade?.score??"未评分"}</td><td>{column.score?.possible??"—"}</td></tr>)}</tbody></table>}</div>;
}
function ContentDetail({ id, close }: { id: string; close: () => void }) {
  const qc = useQueryClient();
  const content = useData<Item>("/content/" + id);
  const documents = useData("/content/" + id + "/documents");
  const node = content.data;
  const [selecting, setSelecting] = useState(false), [selected, setSelected] = useState<string[]>([]), [downloading, setDownloading] = useState(false), [downloadError, setDownloadError] = useState("");
  const [fileProgress,setFileProgress]=useState<Record<string,DownloadProgress>>({});
  async function downloadFile(file:Item){
    try{await downloadDocument(file.id,file.displayName,file.byteSize,value=>setFileProgress(progress=>({...progress,[file.id]:value})));}
    catch(error){setDownloadError((error as Error).message);}
    finally{setFileProgress(progress=>{const next={...progress};delete next[file.id];return next;});}
  }
  async function downloadSelected() {
    setDownloading(true);setDownloadError("");
    const files=documents.data!.filter(file=>selected.includes(file.id));
    try {await downloadDocumentSelection(files,(id,value)=>setFileProgress(progress=>({...progress,[id]:value})));}
    catch(error){setDownloadError((error as Error).message);}
    finally{setDownloading(false);setFileProgress(progress=>{const next={...progress};for(const file of files)delete next[file.id];return next;});}
  }
  function closeContent() {
    qc.invalidateQueries({ queryKey: ["/documents?courseId=" + node?.courseId] });
    close();
  }
  return <Modal title={node?.title || "课程内容"} close={closeContent} className="course-file-browser">
          <ErrorText error={content.error || documents.error || downloadError} />
          {content.isPending && <p>加载中…</p>}
          {node && <>
          {node.bodyText && <p className="preserve">{node.bodyText}</p>}
          <div className="course-file-toolbar">
            {!!documents.data?.length && <div className="course-file-selection">
              <button type="button" className="secondary" onClick={() => {setSelecting(!selecting);setSelected([]);}}>{selecting ? "取消多选" : "多选"}</button>
              {selecting && <label className="checkbox-line"><input type="checkbox" aria-label="全选文件" checked={selected.length === documents.data!.length} onChange={e => setSelected(e.target.checked ? documents.data!.map(file => file.id) : [])} />全选</label>}
            </div>}
            {selecting && <button type="button" className="secondary" disabled={!selected.length || downloading} onClick={downloadSelected}><Download size={16}/>{`下载所选${selected.length ? ` (${selected.length})` : ""}`}</button>}
            {node.sourceUrl && <a className="secondary" href={node.sourceUrl} target="_blank" rel="noreferrer">打开原文 <ArrowUpRight size={16}/></a>}
          </div>
          {documents.data && documents.data.length > 0 && <>
            <div className="course-file-grid">
              {documents.data.map(file => {
                const extension = file.displayName.split(".").pop().toUpperCase();
                const progress=fileProgress[file.id];
                const percent=progress&&progress.total>0?Math.min(100,progress.loaded/progress.total*100):undefined;
                return <article className={"course-file-tile " + (selected.includes(file.id) ? "selected" : "")} key={file.id}>
                  {progress && <svg className={"file-card-progress"+(percent===undefined?" is-indeterminate":"")} role="progressbar" aria-label={file.displayName+" 下载进度"} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent===undefined?undefined:Math.floor(percent)}>
                    <rect className="file-card-progress-track"/>
                    <rect className="file-card-progress-value" pathLength="100" strokeDasharray="100" strokeDashoffset={100-(percent??25)}/>
                  </svg>}
                  <button className={"course-file-download"+(progress?" is-downloading":"")} disabled={!!progress||downloading} onClick={()=>{setDownloadError("");void downloadFile(file);}} aria-label={"下载 " + file.displayName} title={percent!==undefined?`下载 ${Math.floor(percent)}%`:"下载"}>
                    <Download size={16}/>
                  </button>
                  {selecting && <input type="checkbox" aria-label={"选择 " + file.displayName} checked={selected.includes(file.id)} onChange={e => setSelected(ids => e.target.checked ? [...ids,file.id] : ids.filter(id => id !== file.id))} />}
                  <Link to={"/documents/" + file.id} className="course-file-open" title={file.displayName} onClick={event => {if(selecting){event.preventDefault();setSelected(ids => ids.includes(file.id) ? ids.filter(id => id !== file.id) : [...ids,file.id]);}}}>
                    <span className={"course-file-icon ext-" + extension.toLowerCase()}><FileText size={64} strokeWidth={1.2}/><b>{extension}</b></span>
                    <span className="course-file-name">{file.displayName}</span>
                  </Link>
                </article>;
              })}
            </div>
          </>}
          </>}
        </Modal>;
}
export function ContentBrowser({
  courseId,
  parentId,
  onSelect,
}: {
  courseId: string;
  parentId?: string;
  onSelect: (n: Item) => void;
}) {
  const nodes = useData(
      "/courses/" + courseId + "/content" + params({ parentId }),
    ),
    [opened, setOpened] = useState<string>();
  return (
    <div className="content-tree">
      <ErrorText error={nodes.error} />
      {nodes.data?.map((n) => (
        <div key={n.id}>
          <button
            className={"list-row course-node"+(opened===n.id?" is-open":"")}
            aria-expanded={n.nodeType==="FOLDER"?opened===n.id:undefined}
            onClick={() =>
              n.nodeType === "FOLDER"
                ? setOpened(opened === n.id ? undefined : n.id)
                : onSelect(n)
            }
          >
            <span className="course-node-icon">{n.nodeType==="FOLDER"?<Folder size={19}/>:<Files size={19}/>}</span>
            <span title={n.title}>{n.title}</span>
            <ChevronRight
              className={opened === n.id ? "rotate" : ""}
              size={15}
            />
          </button>
          {opened === n.id && (
            <ContentBrowser
              courseId={courseId}
              parentId={n.id}
              onSelect={onSelect}
            />
          )}
        </div>
      ))}
      {nodes.data?.length === 0 && !parentId && <Empty>暂无课程内容</Empty>}
    </div>
  );
}
function CourseSchedule({code}:{code:string}) {
  const [range]=useState(()=>{
    const start=new Date();start.setDate(start.getDate()-(start.getDay()+6)%7);start.setHours(0,0,0,0);
    const end=new Date(start);end.setDate(end.getDate()+7);
    return {from:start.toISOString(),to:end.toISOString()};
  });
  const events=useData<Item[]>("/calendar"+params(range));
  const meetings=events.data?.filter(event=>event.eventType==="SIS"&&event.title.replace(/\s/g,"").startsWith(code)).sort((a,b)=>a.startsAt.localeCompare(b.startsAt));
  const time=(value:string)=>new Date(value).toLocaleTimeString("zh-CN",{hour:"2-digit",minute:"2-digit",hour12:false});
  return <section className="course-aside-card"><div className="course-section-heading"><h2>上课时间</h2><Link className="icon" to="/calendar" aria-label="查看完整课表" title="查看完整课表"><ArrowUpRight size={16}/></Link></div>
    <ErrorText error={events.error}/><div className="course-meetings">{meetings?.map(event=><div className="course-meeting" key={event.id}><CalendarDays size={15}/><div><strong>{new Date(event.startsAt).toLocaleDateString("zh-CN",{weekday:"short"})}<span>{time(event.startsAt)}–{time(event.endsAt)}</span></strong><p>{event.location}</p></div></div>)}{meetings?.length===0&&<Empty>本周暂无课程安排</Empty>}</div>
  </section>;
}
