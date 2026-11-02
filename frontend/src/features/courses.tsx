import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Download,
  FileText,
  GraduationCap,
  Files,
  CalendarDays,
  Sparkles,
  Plus,
  ArrowUpRight,
  Trash2,
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
export function Courses() {
  const [q, setQ] = useState(""),
    [create, setCreate] = useState(false),
    [term, setTerm] = useState(""),
    [archived, setArchived] = useState(false);
  const data = useData("/courses");
  return (
    <>
      <PageHeader title="课程中心">
        <SearchBox value={q} onChange={setQ} placeholder="搜索课程" />
        <button className="primary" onClick={() => setCreate(true)}>
          <Plus size={17} />
          添加课程
        </button>
      </PageHeader>
      <div className="filters">
        <input
          aria-label="筛选学期"
          placeholder="筛选学期"
          value={term}
          onChange={(e) => setTerm(e.target.value)}
        />
        <label className="checkbox-line">
          <input
            type="checkbox"
            checked={archived}
            onChange={(e) => setArchived(e.target.checked)}
          />
          显示归档课程
        </label>
      </div>
      <ErrorText error={data.error} />
      <div className="course-grid">
        {data.data
          ?.filter(
            (c) =>
              (!term || c.term?.includes(term)) && (archived || !c.archived) &&
              (!q || `${c.code || ""} ${c.customName || c.name}`.toLowerCase().includes(q.toLowerCase())),
          )
          .map((c, i) => (
            <Link
              to={"/courses/" + c.id}
              key={c.id}
              className={"course-card tone-" + (i % 3)}
            >
              <span className="course-code">{c.code}</span>
              <div className="course-icon">
                <GraduationCap size={32} />
              </div>
              <h2>{c.customName || c.name}</h2>
              <div>
                <span>{c.term}</span>
                <ArrowUpRight size={20} />
              </div>
            </Link>
          ))}
      </div>
      {data.data?.length === 0 && (
        <Empty>还没有课程，连接 Blackboard 或手动添加。</Empty>
      )}
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
    </>
  );
}
export function CourseDetail() {
  const { id } = useParams();
  const qc = useQueryClient();
  const course = useData<Item>("/courses/" + id),
    ann = useData("/announcements?courseId=" + id),
    notes = useData("/courses/" + id + "/notes"),
    docs = useData("/documents?courseId=" + id);
  const [edit, setEdit] = useState(false),
    [note, setNote] = useState<Item | null | undefined>(),
    [node, setNode] = useState<Item | null>(null);
  return (
    <>
      <Link to="/courses" className="back">
        <ChevronLeft size={16} />
        课程中心
      </Link>
      <PageHeader
        title={course.data?.customName || course.data?.name || "课程"}
      >
        <button className="secondary" onClick={() => setEdit(true)}>
          <Pencil size={16} />
          编辑
        </button>
        <Link className="primary" to={"/copilot?courseId=" + id}>
          <Sparkles size={16} />
          询问 Copilot
        </Link>
      </PageHeader>
      <ErrorText error={course.error || ann.error || docs.error || notes.error} />
      <div className="detail-grid">
        <section className="card">
          <h2>课程内容</h2>
          <ContentBrowser courseId={id!} onSelect={setNode} />
          <h2 className="section-gap">文档资料</h2>
          <div className="course-scroll" role="region" aria-label="文档资料" tabIndex={0}>
          {docs.data?.map((d) => (
            <Link className="list-row" to={"/documents/" + d.id} key={d.id}>
              <Files size={16} />
              <span>{d.displayName}</span>
              <ChevronRight size={16} />
            </Link>
          ))}
          </div>
          <Gradebook courseId={id!}/>
        </section>
        <section className="card">
          <div className="section-head">
            <h2>课程笔记</h2>
            <button
              className="icon"
              aria-label="新建笔记"
              onClick={() => setNote(null)}
            >
              <Plus size={19} />
            </button>
          </div>
          {notes.data?.map((n) => (
            <button className="list-row" key={n.id} onClick={() => setNote(n)}>
              <span>{n.title}</span>
              <Pencil size={15} />
            </button>
          ))}
          {notes.data?.length === 0 && <Empty>写下这门课的想法</Empty>}
          <h2 className="section-gap">公告</h2>
          <div className="course-scroll" role="region" aria-label="课程公告" tabIndex={0}>
          {ann.data?.map((a) => (
            <details
              className="announcement"
              key={a.id}
              onToggle={(e) => {
                if (e.currentTarget.open)
                  api("/announcements/" + a.id, "PATCH", { isRead: true });
              }}
            >
              <summary>{a.title}</summary>
              <time>{date(a.publishedAt)}</time>
              <p>{a.bodyText}</p>
            </details>
          ))}
          {ann.data?.length === 0 && <Empty>暂无公告</Empty>}
          </div>
        </section>
      </div>
      <MeetingRules courseId={id!} />
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
    </>
  );
}
function Gradebook({courseId}:{courseId:string}) {
  const [open,setOpen]=useState(false);
  const grades=useQuery<Item[]>({queryKey:["/courses/"+courseId+"/grades"],queryFn:()=>api("/courses/"+courseId+"/grades"),enabled:open});
  return <details className="course-gradebook section-gap" onToggle={event=>setOpen(event.currentTarget.open)}>
    <summary>成绩簿</summary>
    <ErrorText error={grades.error}/>
    {grades.data?.length===0&&<Empty>暂无成绩项目</Empty>}
    {!!grades.data?.length&&<div className="course-scroll"><table><thead><tr><th>项目</th><th>成绩</th><th>满分</th></tr></thead><tbody>{grades.data.map(column=><tr key={column.id}><td>{column.name}</td><td>{column.grade?.exempt?"已豁免":column.grade?.score??"未评分"}</td><td>{column.score?.possible??"—"}</td></tr>)}</tbody></table></div>}
  </details>;
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
            className="list-row"
            onClick={() =>
              n.nodeType === "FOLDER"
                ? setOpened(opened === n.id ? undefined : n.id)
                : onSelect(n)
            }
          >
            <Files size={16} />
            <span>{n.title}</span>
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
export function MeetingRules({ courseId }: { courseId: string }) {
  const rules = useData("/courses/" + courseId + "/meeting-rules"),
    [create, setCreate] = useState(false),
    qc = useQueryClient();
  return (
    <section className="card section-gap">
      <div className="section-head">
        <h2>上课时间</h2>
        <button className="secondary" onClick={() => setCreate(true)}>
          <Plus size={16} />
          添加课表规则
        </button>
      </div>
      {rules.data?.map((r) => (
        <div className="list-row" key={r.id}>
          <CalendarDays size={17} />
          <span>
            周{"一二三四五六日"[r.weekday - 1]} {r.startsTime}–{r.endsTime} ·{" "}
            {r.location}
            <small className="muted">
              {" "}
              {r.validFrom} 至 {r.validTo}
            </small>
          </span>
          <button
            className="icon"
            aria-label="删除课表规则"
            onClick={async () => {
              await api("/meeting-rules/" + r.id, "DELETE");
              qc.invalidateQueries();
            }}
          >
            <Trash2 size={16} />
          </button>
        </div>
      ))}
      {rules.data?.length === 0 && <Empty>添加规则后自动生成课程日历</Empty>}
      {create && (
        <FormModal
          title="添加课表规则"
          close={() => setCreate(false)}
          submit={(d) =>
            api("/courses/" + courseId + "/meeting-rules", "POST", {
              ...d,
              weekday: Number(d.weekday),
            })
          }
        >
          <Field label="星期">
            <select name="weekday">
              {[1, 2, 3, 4, 5, 6, 7].map((v) => (
                <option key={v} value={v}>
                  周{"一二三四五六日"[v - 1]}
                </option>
              ))}
            </select>
          </Field>
          <div className="form-grid">
            <Field label="开始时间">
              <input type="time" name="startsTime" required />
            </Field>
            <Field label="结束时间">
              <input type="time" name="endsTime" required />
            </Field>
            <Field label="生效日期">
              <input type="date" name="validFrom" required />
            </Field>
            <Field label="结束日期">
              <input type="date" name="validTo" required />
            </Field>
          </div>
          <Field label="地点">
            <input name="location" />
          </Field>
        </FormModal>
      )}
    </section>
  );
}
