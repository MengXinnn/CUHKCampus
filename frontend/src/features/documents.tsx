import { DownloadIcon } from "../components/DownloadIcon";
import { beginRequest } from "../requestProgress";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Files,
  FileText,
  Folder,
  FolderPlus,
  Trash2,
  Download,
  Upload,
  ChevronRight,
  ChevronLeft,
  LayoutGrid,
  List,
  ArrowDownUp,
  Pencil,
  Info,
  FolderInput,
  Check,
  PanelRight,
  Languages,
  X,
} from "lucide-react";
import { downloadFiles, api, params, csrf, date, type Item } from "../api";
import {
  Markdown,
  Empty,
  ErrorText,
  useData,
  SearchBox,
  PageHeader,
  Modal,
  Field,
  FormModal,
} from "../components/ui";
import { ChatPanel } from "./copilot";
import { FileProgress, type Transfer } from "../components/FileProgress";
import { SlidePreview } from "./SlidePreview";
import { PdfPreview } from "./PdfPreview";
import "./documents.css";
const officeTypes = [
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  "application/msword",
  "application/vnd.ms-powerpoint",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
];
const fileType = (d: Item) =>
  d.displayName.split(".").pop()?.toUpperCase() || d.mimeType;
const fileSize = (bytes: number) =>
  bytes >= 1048576
    ? (bytes / 1048576).toFixed(1) + " MB"
    : (bytes / 1024).toFixed(0) + " KB";
export function Documents() {
  const [search, setSearch] = useSearchParams(),
    folderId = search.get("folderId"),
    q = search.get("q") || "";
  const [view, setView] = useState(
      localStorage.getItem("campus-file-view") || "grid",
    ),
    [sort, setSort] = useState("name"),
    [ascending, setAscending] = useState(true),
    [selected, setSelected] = useState<string[]>([]),
    [selectedFolders, setSelectedFolders] = useState<string[]>([]),
    [error, setError] = useState(""),
    [creating, setCreating] = useState(false),
    [renaming, setRenaming] = useState<Item>(),
    [moving, setMoving] = useState(false),
    [details, setDetails] = useState<Item>(),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    setSelected([]);
    setSelectedFolders([]);
  }, [folderId, q]);
  const qc = useQueryClient(),
    data = useData(
      "/documents" +
        params({ folderId: q ? undefined : folderId || "root", filename: q }),
    ),
    folders = useData(
      "/document-folders" + params({ parentId: folderId || "root" }),
    );
  const current = useQuery<Item>({
    queryKey: ["folder", folderId],
    queryFn: () => api("/document-folders/" + folderId),
    enabled: !!folderId,
  });
  const docs = [...(data.data || [])].sort((a, b) => {
    let n =
      sort === "size"
        ? a.byteSize - b.byteSize
        : sort === "time"
          ? String(a.updatedAt || a.createdAt || "").localeCompare(
              b.updatedAt || b.createdAt || "",
            )
          : sort === "type"
            ? fileType(a).localeCompare(fileType(b))
            : a.displayName.localeCompare(b.displayName, "zh-CN");
    return ascending ? n : -n;
  });
  function openFolder(id?: string) {
    setSearch(id ? { folderId: id } : {});
    setSelected([]);
  }
  async function action(fn: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await fn();
      setSelected([]);
      setSelectedFolders([]);
      await qc.invalidateQueries();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function toggle(id: string) {
    setSelected((s) =>
      s.includes(id) ? s.filter((v) => v !== id) : [...s, id],
    );
  }
  const ancestors = current.data?.ancestors || [];
  const visibleFolders = q
    ? []
    : [...(folders.data || [])].sort((a, b) =>
        ascending
          ? a.name.localeCompare(b.name, "zh-CN")
          : b.name.localeCompare(a.name, "zh-CN"),
      );
  const selectedCount = selected.length + selectedFolders.length;
  const allSelected =
    docs.length + visibleFolders.length > 0 &&
    selectedCount === docs.length + visibleFolders.length;
  return (
    <div className="file-browser">
      <PageHeader title="文档库">
        <SearchBox
          value={q}
          onChange={(q) => {
            setSearch({ ...Object.fromEntries(search), q });
            setSelected([]);
          }}
          placeholder="搜索文件名"
        />
        <button className="secondary" onClick={() => setCreating(true)}>
          <FolderPlus size={17} />
          新建文件夹
        </button>
        <label className="primary upload">
          <Upload size={17} />
          {busy ? "处理中…" : "上传文档"}
          <input
            type="file"
            multiple
            disabled={busy}
            onChange={(e) => {
              const files = Array.from(e.target.files || []);
              action(async () => {
                for (const file of files) {
                  const fd = new FormData();
                  fd.append("file", file);
                  if (folderId) fd.append("folderId", folderId);
                  await api("/documents", "POST", fd);
                }
              });
              e.target.value = "";
            }}
          />
        </label>
      </PageHeader>
      <div className="file-toolbar">
        <nav className="breadcrumbs" aria-label="文档路径">
          <button onClick={() => openFolder()}>文档库</button>
          {ancestors.map((f: Item) => (
            <span key={f.id}>
              <ChevronRight size={13} />
              <button onClick={() => openFolder(f.id)}>{f.name}</button>
            </span>
          ))}
          {current.data && (
            <span>
              <ChevronRight size={13} />
              <strong>{current.data.name}</strong>
            </span>
          )}
          {q && <span className="muted">搜索结果</span>}
        </nav>
        <div className="file-view-tools">
          <select
            aria-label="排序方式"
            value={sort}
            onChange={(e) => setSort(e.target.value)}
          >
            <option value="name">名称</option>
            <option value="type">类型</option>
            <option value="size">大小</option>
            <option value="time">修改时间</option>
          </select>
          <button
            className="icon"
            aria-label={ascending ? "切换降序" : "切换升序"}
            onClick={() => setAscending(!ascending)}
          >
            <ArrowDownUp size={16} />
          </button>
          <div className="file-view-toggle">
            {[
              [LayoutGrid, "grid", "网格"],
              [List, "list", "列表"],
            ].map(([Icon, value, label]) => {
              const Component = Icon as typeof List;
              return (
                <button
                  key={String(value)}
                  className={view === value ? "active" : ""}
                  aria-label={String(label)}
                  onClick={() => {
                    setView(String(value));
                    localStorage.setItem("campus-file-view", String(value));
                  }}
                >
                  <Component size={17} />
                </button>
              );
            })}
          </div>
        </div>
      </div>
      <ErrorText
        error={error || data.error || folders.error || current.error}
      />
      <div className="file-selection">
        <label>
          <input
            type="checkbox"
            aria-label="选择全部"
            checked={allSelected}
            onChange={() => {
              setSelected(allSelected ? [] : docs.map((d) => d.id));
              setSelectedFolders(
                allSelected ? [] : visibleFolders.map((f) => f.id),
              );
            }}
          />
          <span>
            {selectedCount
              ? "已选 " + selectedCount + " 项"
              : docs.length +
                " 个文件" +
                (!q ? " · " + (folders.data?.length || 0) + " 个文件夹" : "")}
          </span>
        </label>
        {selectedCount > 0 && (
          <div className="actions">
            <button className="secondary" onClick={() => setMoving(true)}>
              <FolderInput size={15} />
              移动
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() =>
                action(() => downloadFiles(selected, selectedFolders))
              }
            >
              <Download size={15} />
              下载
            </button>
            <button
              className="secondary danger"
              disabled={busy}
              onClick={() =>
                action(() =>
                  Promise.all([
                    ...selected.map((id) => api("/documents/" + id, "DELETE")),
                    ...selectedFolders.map((id) =>
                      api("/document-folders/" + id, "DELETE"),
                    ),
                  ]),
                )
              }
            >
              <Trash2 size={15} />
              删除
            </button>
            <button
              className="icon"
              aria-label="取消选择"
              onClick={() => {
                setSelected([]);
                setSelectedFolders([]);
              }}
            >
              <X size={16} />
            </button>
          </div>
        )}
      </div>
      <section className={"file-items " + view}>
        {visibleFolders.map((f) => (
          <article
            className={
              "folder-item " +
              (selectedFolders.includes(f.id) ? "selected" : "")
            }
            key={f.id}
          >
            <input
              className="file-select"
              type="checkbox"
              aria-label={"选择 " + f.name}
              checked={selectedFolders.includes(f.id)}
              onChange={() =>
                setSelectedFolders((old) =>
                  old.includes(f.id)
                    ? old.filter((v) => v !== f.id)
                    : [...old, f.id],
                )
              }
            />
            <button className="folder-main" onClick={() => openFolder(f.id)}>
              <Folder size={view === "grid" ? 35 : 22} fill="currentColor" />
              <strong>{f.name}</strong>
              {view === "list" && <span className="muted">文件夹</span>}
            </button>
            <div className="file-item-actions">
              <button
                className="icon"
                aria-label={"重命名 " + f.name}
                onClick={() => setRenaming(f)}
              >
                <Pencil size={15} />
              </button>
              <button
                className="icon"
                aria-label={"删除 " + f.name}
                onClick={() =>
                  action(() => api("/document-folders/" + f.id, "DELETE"))
                }
              >
                <Trash2 size={15} />
              </button>
            </div>
          </article>
        ))}
        {docs.map((d) => (
          <article
            key={d.id}
            className={
              "file-item " + (selected.includes(d.id) ? "selected" : "")
            }
          >
            <input
              className="file-select"
              type="checkbox"
              aria-label={"选择 " + d.displayName}
              checked={selected.includes(d.id)}
              onChange={() => toggle(d.id)}
            />
            <Link className="file-main" to={"/documents/" + d.id}>
              {d.mimeType?.startsWith("image/") ? (
                <div className="file-cover image">
                  <img
                    src={"/api/v1/documents/" + d.id + "/file"}
                    alt=""
                    loading="lazy"
                  />
                </div>
              ) : (
                <div className={"file-cover type-" + fileType(d).toLowerCase()}>
                  <FileText size={view === "grid" ? 39 : 22} />
                  {view === "grid" && <span>{fileType(d)}</span>}
                </div>
              )}
              <div className="file-name">
                <strong>{d.displayName}</strong>
                <span>
                  {fileType(d)} · {fileSize(d.byteSize)}
                </span>
              </div>
              {view === "list" && (
                <time>{date(d.updatedAt || d.createdAt)}</time>
              )}
            </Link>
            <div className="file-item-actions">
              <button
                className="icon"
                aria-label={"详情 " + d.displayName}
                onClick={() => setDetails(d)}
              >
                <Info size={16} />
              </button>
              <a
                className="icon"
                aria-label={"下载 " + d.displayName}
                href={"/api/v1/documents/" + d.id + "/file?download=true"}
                download={d.displayName}
              >
                <Download size={16} />
              </a>
            </div>
          </article>
        ))}
      </section>
      {data.isSuccess && folders.isSuccess && !docs.length && (q || !folders.data?.length) && (
        <Empty>
          {q ? "没有匹配的文件" : "上传文档，或创建文件夹整理资料。"}
        </Empty>
      )}
      {creating && (
        <FormModal
          title="新建文件夹"
          close={() => setCreating(false)}
          submit={(d) =>
            api("/document-folders", "POST", {
              name: d.name,
              parentId: folderId,
            })
          }
        >
          <Field label="名称">
            <input name="name" required autoFocus />
          </Field>
        </FormModal>
      )}
      {renaming && (
        <FormModal
          title="重命名文件夹"
          close={() => setRenaming(undefined)}
          submit={(d) => api("/document-folders/" + renaming.id, "PATCH", d)}
        >
          <Field label="名称">
            <input
              name="name"
              defaultValue={renaming.name}
              required
              autoFocus
            />
          </Field>
        </FormModal>
      )}
      {moving && (
        <MoveDialog
          ids={selected}
          folderIds={selectedFolders}
          close={() => setMoving(false)}
          moved={() => {
            setSelected([]);
            setSelectedFolders([]);
            qc.invalidateQueries();
          }}
        />
      )}
      {details && (
        <Modal title={details.displayName} close={() => setDetails(undefined)}>
          <div className="file-details">
            <FileText size={42} />
            <dl>
              {[
                ["类型", fileType(details)],
                ["大小", fileSize(details.byteSize)],
                ["创建", date(details.createdAt)],
                ["修改", date(details.updatedAt)],
                [
                  "来源",
                  (
                    {
                      UPLOAD: "上传",
                      BLACKBOARD: "Blackboard",
                      OUTLOOK: "Outlook",
                    } as Record<string, string>
                  )[details.origin],
                ],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          </div>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const displayName = String(
                new FormData(e.currentTarget).get("displayName"),
              );
              action(async () => {
                await api("/documents/" + details.id, "PATCH", { displayName });
                setDetails(undefined);
              });
            }}
          >
            <Field label="文件名">
              <input
                name="displayName"
                defaultValue={details.displayName}
                required
              />
            </Field>
            <div className="actions">
              <button className="primary">保存</button>
              <button
                className="secondary"
                type="button"
                onClick={() => {
                  setSelected([details.id]);
                  setSelectedFolders([]);
                  setDetails(undefined);
                  setMoving(true);
                }}
              >
                <FolderInput size={16} />
                移动
              </button>
              <button
                className="secondary danger"
                type="button"
                onClick={() =>
                  action(async () => {
                    await api("/documents/" + details.id, "DELETE");
                    setDetails(undefined);
                  })
                }
              >
                <Trash2 size={16} />
                删除
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
function MoveDialog({
  ids,
  folderIds,
  close,
  moved,
}: {
  ids: string[];
  folderIds: string[];
  close: () => void;
  moved: () => void;
}) {
  const [destination, setDestination] = useState<string | null>(null),
    [error, setError] = useState("");
  const folders = useData(
    "/document-folders" + params({ parentId: destination || "root" }),
  );
  const current = useQuery<Item>({
    queryKey: ["folder", destination],
    queryFn: () => api("/document-folders/" + destination),
    enabled: !!destination,
  });
  return (
    <Modal title="移动到" close={close}>
      <div className="move-path">
        <button
          className="icon"
          aria-label="上一级"
          disabled={!destination || current.isPending}
          onClick={() => setDestination(current.data?.parentId || null)}
        >
          <ChevronLeft size={17} />
        </button>
        <strong>{current.data?.name || "文档库"}</strong>
      </div>
      <div className="move-folders">
        {folders.data
          ?.filter((f) => !folderIds.includes(f.id))
          .map((f) => (
            <button
              className="list-row"
              key={f.id}
              onClick={() => setDestination(f.id)}
            >
              <Folder size={20} />
              <span>{f.name}</span>
              <ChevronRight size={16} />
            </button>
          ))}
      </div>
      <ErrorText error={error || folders.error} />
      <button
        className="primary"
        onClick={async () => {
          try {
            await Promise.all([
              ...ids.map((id) =>
                api("/documents/" + id, "PATCH", { folderId: destination }),
              ),
              ...folderIds.map((id) =>
                api("/document-folders/" + id, "PATCH", {
                  parentId: destination,
                }),
              ),
            ]);
            moved();
            close();
          } catch (e) {
            setError((e as Error).message);
          }
        }}
      >
        移动 {ids.length + folderIds.length} 项到此处
      </button>
    </Modal>
  );
}
async function translateStream(
  id: string,
  language: string,
  image: string,
  sourceText: string,
  onDelta: (text: string) => void,
) {
    const response = await fetch("/api/v1/documents/" + id + "/translate", {
      method: "POST",
      headers: { ...csrf(), "Content-Type": "application/json" },
      body: JSON.stringify({ targetLanguage: language, image, sourceText }),
    });
    if (!response.ok) throw new Error(await response.text());
    const reader = response.body!.getReader(),
      decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n");
      let end;
      while ((end = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const event = frame
            .split("\n")
            .find((l) => l.startsWith("event:"))
            ?.slice(6)
            .trim(),
          data = frame
            .split("\n")
            .filter((l) => l.startsWith("data:"))
            .map((l) => l.slice(5).trim())
            .join("\n");
        if (!data) continue;
        const parsed = JSON.parse(data);
        if (event === "delta") onDelta(parsed.content);
        if (event === "error") throw new Error(parsed.message);
      }
    }
}
export function DocumentView() {
  const { id } = useParams(),
    doc = useData<Item>("/documents/" + id),
    [copilot, setCopilot] = useState(() => window.matchMedia("(min-width: 1000px)").matches),
    [translated, setTranslated] = useState(true),
    [currentPage, setCurrentPage] = useState({ number: 1, text: "", image: "" }),
    [translationHeight, setTranslationHeight] = useState(60),
    [translationDragging,setTranslationDragging]=useState(false),
    [translation, setTranslation] = useState(""),
    [targetLanguage, setLanguage] = useState("中文"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [ready, setReady] = useState(false),
    [transfer, setTransfer] = useState<Transfer>({ loaded: 0, total: 0 }),
    [transferError, setTransferError] = useState(""),
    [downloadProgress, setDownloadProgress] = useState<Transfer | null>(null),
    [downloadError, setDownloadError] = useState("");
  const translationGesture=useRef({startY:0,height:60,moved:false});
  const translationCache=useRef(new Map<string,string>());
  const translationKey=`${id}:${currentPage.number}:${targetLanguage}`;
  const visibleTranslationKey=useRef(translationKey);
  visibleTranslationKey.current=translationKey;
  useEffect(()=>{
    if(!translationDragging)return;
    document.body.classList.add("translation-dragging");
    return ()=>document.body.classList.remove("translation-dragging");
  },[translationDragging]);
  function translationLimit(element:HTMLElement){return element.closest<HTMLElement>(".reader-document")!.clientHeight*.75;}
  useEffect(() => {
    if (!doc.data) return;
    if (doc.data.storageKey) { setReady(true); return; }
    const source = new EventSource("/api/v1/documents/" + id + "/prepare");
    source.addEventListener("progress", event => setTransfer(JSON.parse(event.data)));
    source.addEventListener("ready", () => { source.close(); setReady(true); });
    source.addEventListener("failed", event => { source.close(); setTransferError(JSON.parse(event.data).message); });
    source.onerror = () => { source.close(); setTransferError("文件下载连接已中断"); };
    return () => source.close();
  }, [id, doc.data?.storageKey]);
  useEffect(() => {
    if (doc.data && !ready && !transferError) return beginRequest();
  }, [!!doc.data, ready, transferError]);
  const extracted = useQuery<{ content: string }>({
    queryKey: ["document-text", id],
    queryFn: () => api("/documents/" + id + "/text"),
    enabled: ready && !!doc.data && doc.data.mimeType?.startsWith("text/"),
  });
  const onPage = useCallback((number: number, text: string, image: string) => {
    setCurrentPage({ number, text, image });
  }, []);
  useEffect(() => {setTranslation(translationCache.current.get(translationKey)||"");setError("");},[translationKey]);
  const url = "/api/v1/documents/" + id + "/file";
  async function downloadOriginal() {
    setDownloadError("");
    setDownloadProgress({ loaded: 0, total: doc.data!.byteSize });
    try {
      const response = await fetch(url + "?download=true");
      if (!response.ok) throw new Error(await response.text());
      const total = Number(response.headers.get("Content-Length")) || doc.data!.byteSize;
      const reader = response.body!.getReader();
      const chunks: BlobPart[] = [];
      let loaded = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value.slice().buffer);
        loaded += value.byteLength;
        setDownloadProgress({ loaded, total });
      }
      const objectUrl = URL.createObjectURL(new Blob(chunks, { type: doc.data!.mimeType }));
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = doc.data!.displayName;
      link.click();
      URL.revokeObjectURL(objectUrl);
    } catch (error) { setDownloadError((error as Error).message); }
    finally { setDownloadProgress(null); }
  }
  async function translate() {
    setBusy(true);
    setTranslated(true);
    const key=translationKey;
    translationCache.current.set(key,"");
    setTranslation("");
    setError("");
    try {
      await translateStream(id!, targetLanguage, currentPage.image, currentPage.text, (s) => {
        const text=(translationCache.current.get(key)||"")+s;
        translationCache.current.set(key,text);
        if(visibleTranslationKey.current===key)setTranslation(text);
      },
      );
    } catch (e) {
      if(visibleTranslationKey.current===key)setError((e as Error).message);
    } finally {
      if(visibleTranslationKey.current===key)setTranslation(translationCache.current.get(key)||"");
      setBusy(false);
    }
  }
  const translationBar = (
    <div className={"reader-translation expanded"+(translationDragging?" is-dragging":"")} style={{ height: translationHeight, flex: "0 0 auto", maxHeight: "75%" }}>
      <span className={"translation-collapsed-title" + (translationHeight > 80 || !!translation.trim() ? " is-faded" : "")} aria-hidden={translationHeight > 80 || !!translation.trim()}>翻译文本</span>
      <div className="translation-resize" role="separator" aria-label="调整翻译区域高度" aria-orientation="horizontal" tabIndex={0}
        onPointerDown={e => {
          if(e.button!==0)return;
          e.preventDefault();
          const height=e.currentTarget.parentElement!.getBoundingClientRect().height;
          translationGesture.current={startY:e.clientY,height,moved:false};
          setTranslationHeight(height);setTranslationDragging(true);
          e.currentTarget.setPointerCapture(e.pointerId);
        }}
        onPointerMove={e=>{
          if(!e.currentTarget.hasPointerCapture(e.pointerId))return;
          e.preventDefault();
          const gesture=translationGesture.current,offset=gesture.startY-e.clientY;
          if(Math.abs(offset)>4)gesture.moved=true;
          if(gesture.moved)setTranslationHeight(Math.min(translationLimit(e.currentTarget),Math.max(65,gesture.height+offset)));
        }}
        onPointerUp={e=>{
          if(!e.currentTarget.hasPointerCapture(e.pointerId))return;
          e.preventDefault();
          e.currentTarget.releasePointerCapture(e.pointerId);setTranslationDragging(false);
        }}
        onPointerCancel={()=>setTranslationDragging(false)} onLostPointerCapture={()=>setTranslationDragging(false)}
        />
      <div className="reader-translation-tools">
        {translated && (
          <div className="actions">

            <div className="translation-language">
            <select
              aria-label="目标语言"
              value={targetLanguage}
              onChange={(e) => setLanguage(e.target.value)}
            >
              <option>中文</option>
              <option>英文</option>
            </select>
            </div>
            <button className="translation-action" aria-label="翻译当前页" title={busy ? "翻译中…" : "翻译当前页"} aria-busy={busy} disabled={busy || !currentPage.image} onClick={translate}>
              <Languages size={16} /><span>{busy ? "翻译中" : "翻译"}</span>
            </button>
          </div>
        )}
      </div>
      {translated && (
        <div className="reader-translation-text prose">
          <ErrorText error={error} />
          <Markdown>{translation}</Markdown>
        </div>
      )}
    </div>
  );
  return (
    <section
      className={"document-reader " + (!copilot ? "copilot-collapsed" : "")}
    >
      <header className="reader-header">
        <Link to="/documents" className="reader-back" aria-label="返回文档库">
          <ChevronLeft size={18} />
          <span>文档库</span>
        </Link>
        <span className="reader-separator" />
        <span className="reader-filename" title={doc.data?.displayName}>
          {doc.data?.displayName}
        </span>
        <button className={"icon reader-download"+(downloadProgress?" is-downloading":"")} disabled={!ready || !!downloadProgress} onClick={downloadOriginal} aria-label="下载原文件"
          title={downloadProgress ? (downloadProgress.total > 0 ? `下载 ${Math.min(100,Math.floor(downloadProgress.loaded/downloadProgress.total*100))}%` : "下载中") : "下载原文件"}>
          <DownloadIcon progress={downloadProgress} label="原文件下载进度" size={18}/>
        </button>
        <button
          className={"icon reader-copilot-toggle " + (copilot ? "active" : "")}
          aria-label={copilot ? "收起 Copilot" : "展开 Copilot"}
          title="Copilot"
          onClick={() => setCopilot(!copilot)}
        >
          <PanelRight size={19} />
        </button>
      </header>
      <ErrorText error={doc.error} />
      <div className="reader-body">
        {doc.data && (
          <div className="reader-document">
            {downloadError && <div className="reader-download-progress"><ErrorText error={downloadError} /></div>}
            {!ready ? <div className="reader-preparing">
              <ErrorText error={transferError} />
              {!transferError && <FileProgress progress={transfer} label="从 Blackboard 下载" />}
            </div> : ["application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation"].includes(doc.data.mimeType) ? (
              <SlidePreview id={id!} translation={translationBar} onPage={onPage} />
            ) : doc.data.mimeType === "application/pdf" ||
            officeTypes.includes(doc.data.mimeType) ? (
              <PdfPreview
                url={
                  doc.data.mimeType === "application/pdf"
                    ? url
                    : "/api/v1/documents/" + id + "/preview"
                }
                translation={translationBar}
                onPage={onPage}
                preparingLabel={officeTypes.includes(doc.data.mimeType) ? "正在生成预览" : "加载预览"}
              />
            ) : (
              <div className="reader-nonpdf">
                <div
                  className="reader-scroll"
                  tabIndex={0}
                  onPointerDown={(e) =>
                    e.currentTarget.focus({ preventScroll: true })
                  }
                  onKeyDown={(e) => {
                    if ((e.key === "ArrowUp" || e.key === "ArrowDown") && !(e.target instanceof HTMLVideoElement)) {
                      e.preventDefault();
                      e.stopPropagation();
                      e.currentTarget.scrollBy(
                        0,
                        e.key === "ArrowDown" ? 60 : -60,
                      );
                    }
                  }}
                >
                  {doc.data.mimeType?.startsWith("image/") ? (
                    <img src={url} alt={doc.data.displayName} />
                  ) : doc.data.mimeType?.startsWith("video/") ? (
                    <video className="reader-video" controls preload="metadata" aria-label={doc.data.displayName}>
                      <source src={url} type={doc.data.mimeType} />
                    </video>
                  ) : doc.data.mimeType?.startsWith("text/") ? (
                    <div className="reader-text preserve">
                      <ErrorText error={extracted.error} />
                      {extracted.data?.content}
                    </div>
                  ) : (
                    <div className="reader-binary">
                      <FileText size={48} strokeWidth={1.3} />
                      <h2>{doc.data.displayName}</h2>
                      <dl>
                        <div><dt>类型</dt><dd>{doc.data.mimeType}</dd></div>
                        <div><dt>大小</dt><dd>{fileSize(doc.data.byteSize)}</dd></div>
                        <div><dt>来源</dt><dd>{({ BLACKBOARD: "Blackboard", OUTLOOK: "Outlook", UPLOAD: "上传" } as Record<string, string>)[doc.data.origin]}</dd></div>
                      </dl>
                      <button className="primary" disabled={!!downloadProgress} onClick={downloadOriginal}><Download size={16} />下载原文件</button>
                    </div>
                  )}
                </div>
                {(doc.data.mimeType?.startsWith("text/") || doc.data.mimeType?.startsWith("image/")) && translationBar}
              </div>
            )}
          </div>
        )}
        <aside
          className="reader-copilot"
          inert={!copilot}
          aria-hidden={!copilot}
        >
          <ChatPanel documentId={id} currentPage={currentPage} />
        </aside>
      </div>
    </section>
  );
}
