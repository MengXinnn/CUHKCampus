import { useState, useEffect, useLayoutEffect, useRef, type FormEvent, type ReactNode } from "react";
import { Link, useParams, useNavigate, useLocation } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Sparkles,
  Plus,
  ArrowUpRight,
  ArrowUp,
  ArrowDown,
  Trash2,
  Pencil,
  ChevronRight,
  ChevronLeft,
  Link2,
  X,
  Search,
  GraduationCap,
  CalendarDays,
  FileText,
  Check,
  PanelLeftClose,
  PanelLeftOpen,
  SquarePen,
} from "lucide-react";
import { api, params, chat, type Item } from "../api";
import { Markdown, ErrorText, useData, SearchBox, Modal } from "../components/ui";
type ToolRun = { id: string; name: string; status: string };
const toolNames: Record<string, string> = {
  list_courses: "查询课程", get_course_content: "浏览课程目录", list_announcements: "读取公告",
  read_course_content: "读取课程内容", list_content_documents: "查询课程附件", search_documents: "搜索文档",
  read_document: "读取文档", list_mail: "搜索邮件", read_mail: "读取邮件", list_tasks: "查询任务",
  create_or_update_task: "更新任务", get_calendar_events: "查询日程", plan_study_sessions: "安排学习", sync_outlook: "同步邮件",
};
function ToolActivity({ calls, open, onToggle }: { calls: ToolRun[]; open?: boolean; onToggle?: (open: boolean) => void }) {
  return calls.length ? <details className="tool-activity" open={open} onToggle={event => onToggle?.(event.currentTarget.open)}>
    <summary>使用工具 · {calls.length}</summary>
    <div>{calls.map((call,index) => <div className="tool-run" key={call.id || index}>
      <span>{toolNames[call.name] || call.name}</span>
      <span className={"tool-state " + call.status?.toLowerCase()}>{call.status === "RUNNING" ? "执行中" : call.status === "FAILED" ? "失败" : "完成"}</span>
    </div>)}</div>
  </details> : null;
}
function ThinkingIndicator() {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const started = Date.now();
    const timer = window.setInterval(() => setSeconds(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return <div className="thinking-indicator" role="status">
    <span className="thinking-dots" aria-hidden="true"><i /><i /><i /></span>
    <span>正在思考</span>
    <time aria-label="思考用时">{seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`}</time>
  </div>;
}
export function Copilot() {
  const { conversationId } = useParams(),
    [q, setQ] = useState(""),
    [page, setPage] = useState(0),
    [error, setError] = useState("");
  const conv = useData<{ items: Item[]; total: number }>(
    "/conversations" + params({ q, page, size: 25 }),
  );
  const navigate = useNavigate(),
    qc = useQueryClient();
  const [historyOpen, setHistoryOpen] = useState(
    () => window.matchMedia("(min-width: 761px)").matches && localStorage.getItem("campus:copilot-history") !== "collapsed",
  );
  function newConversation() {
    if (window.matchMedia("(max-width: 760px)").matches) setHistoryOpen(false);
    navigate("/copilot");
  }
  function toggleHistory() {
    const next = !historyOpen;
    setHistoryOpen(next);
    localStorage.setItem("campus:copilot-history", next ? "open" : "collapsed");
  }
  return (
    <div className={"copilot-layout" + (historyOpen ? "" : " history-collapsed")}>
      <aside className="conversation-list" aria-label="聊天历史" inert={!historyOpen}>
        <button className="primary" onClick={newConversation}>
          <Plus size={18} />
          新建对话
        </button>
        <SearchBox
          value={q}
          onChange={(v) => {
            setQ(v);
            setPage(0);
          }}
          placeholder="搜索对话"
        />
        <ErrorText error={error || conv.error} />
        <div className="conversations">
          {conv.data?.items.map((c) => (
            <div
              className={
                "conversation " + (c.id === conversationId ? "active" : "")
              }
              key={c.id}
            >
              <Link
                to={"/copilot/" + c.id}
                onClick={() => {
                  if (window.matchMedia("(max-width: 760px)").matches) setHistoryOpen(false);
                }}
              >
                <span>{c.title}</span>
              </Link>
              <button
                className="icon"
                aria-label="重命名对话"
                onClick={async () => {
                  const title = window.prompt("对话名称", c.title);
                  if (title) {
                    await api("/conversations/" + c.id, "PATCH", { title });
                    qc.invalidateQueries();
                  }
                }}
              >
                <Pencil size={13} />
              </button>
              <button
                className="icon"
                aria-label="删除对话"
                onClick={async () => {
                  await api("/conversations/" + c.id, "DELETE");
                  qc.invalidateQueries();
                  if (c.id === conversationId) navigate("/copilot");
                }}
              >
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
        {(conv.data?.total || 0) > 25 && (
          <div className="pagination">
            <button
              className="icon"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              aria-label="上一页"
            >
              <ChevronLeft size={16} />
            </button>
            <span>{page + 1}</span>
            <button
              className="icon"
              disabled={(page + 1) * 25 >= (conv.data?.total || 0)}
              onClick={() => setPage(page + 1)}
              aria-label="下一页"
            >
              <ChevronRight size={16} />
            </button>
          </div>
        )}
      </aside>
      <ChatPanel
        key={conversationId || "new"}
        conversationId={conversationId}
        headerStart={<button className="icon" onClick={toggleHistory} aria-label={historyOpen ? "收起聊天历史" : "展开聊天历史"} title={historyOpen ? "收起聊天历史" : "展开聊天历史"} aria-expanded={historyOpen}>{historyOpen ? <PanelLeftClose size={19} /> : <PanelLeftOpen size={19} />}</button>}
        headerActions={<button className="icon" onClick={newConversation} aria-label="新建对话" title="新建对话"><SquarePen size={18} /></button>}
      />
    </div>
  );
}
export function ChatPanel({
  conversationId,
  documentId,
  currentPage,
  headerStart,
  headerActions,
}: {
  conversationId?: string;
  documentId?: string;
  currentPage?: { number: number; text: string; image: string };
  headerStart?: ReactNode;
  headerActions?: ReactNode;
}) {
  const [toolRuns, setToolRuns] = useState<ToolRun[]>([]), [toolsOpen, setToolsOpen] = useState(true);
  const answering = useRef(false);
  const [attachment, setAttachment] = useState<{ page: number; image: string } | null>(null);
  const [id, setId] = useState(conversationId),
    [text, setText] = useState(""),
    [stream, setStream] = useState(""),
    [pending, setPending] = useState(false),
    [error, setError] = useState(""),
    [messages, setMessages] = useState<Item[]>([]),
    [page, setPage] = useState(0),
    [total, setTotal] = useState(0);
  const scroll = useRef<HTMLDivElement>(null),
    qc = useQueryClient(),
    navigate = useNavigate();
  const following = useRef(true);
  const [showLatest, setShowLatest] = useState(false);
  function jumpToLatest() {
    following.current = true;
    setShowLatest(false);
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: "instant" });
  }
  const search = new URLSearchParams(useLocation().search);
  const initialCourse = search.get("courseId");
  const [pickerOpen, setPickerOpen] = useState(false),
    [attachmentQuery, setAttachmentQuery] = useState(""),
    [selectedRefs, setSelectedRefs] = useState<Item[]>(initialCourse ? [{ id: initialCourse, type: "COURSE", title: "课程" }] : []);
  const picker = useRef<HTMLDivElement>(null);
  const courses = useQuery<Item[]>({ queryKey: ["/courses"], queryFn: () => api("/courses"), enabled: pickerOpen || !!initialCourse }),
    docs = useQuery<Item[]>({ queryKey: ["/documents"], queryFn: () => api("/documents"), enabled: pickerOpen }),
    events = useQuery<Item[]>({ queryKey: ["/calendar"], queryFn: () => api("/calendar"), enabled: pickerOpen });
  useEffect(() => {
    if (!pickerOpen) return;
    const outside = (event: PointerEvent) => { if (!picker.current?.contains(event.target as Node)) setPickerOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") setPickerOpen(false); };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => { document.removeEventListener("pointerdown", outside); document.removeEventListener("keydown", escape); };
  }, [pickerOpen]);
  const groups = [
    { type: "COURSE", label: "课程", icon: GraduationCap, query: courses, items: courses.data?.filter(v => !v.archived).map(v => ({ ...v, title: v.customName || v.name })) || [] },
    { type: "EVENT", label: "日程", icon: CalendarDays, query: events, items: [...(events.data || [])].sort((a,b) => a.startsAt.localeCompare(b.startsAt)) },
    { type: "DOCUMENT", label: "文档", icon: FileText, query: docs, items: [...(docs.data || [])].sort((a,b) => (b.updatedAt || "").localeCompare(a.updatedAt || "")).map(v => ({ ...v, title: v.displayName })) },
  ];
  function toggleAttachment(type: string, item: Item) {
    const existing = selectedRefs.some(r => r.type === type && r.id === item.id);
    setSelectedRefs(refs => existing ? refs.filter(r => !(r.type === type && r.id === item.id)) : [...refs,
      type === "EVENT" ? { type, id: item.id, title: item.title, startsAt: item.startsAt, endsAt: item.endsAt, location: item.location }
        : { type, id: item.id, title: item.title }]);
  }
  async function loadMessages(active: string, requestedPage?: number) {
    let selected = requestedPage ?? 0;
    let saved = await api<{ items: Item[]; total: number }>(
      "/conversations/" +
        active +
        "/messages" +
        params({ page: selected, size: 50 }),
    );
    if (requestedPage === undefined) {
      selected = Math.max(0, Math.ceil(saved.total / 50) - 1);
      if (selected > 0)
        saved = await api<{ items: Item[]; total: number }>(
          "/conversations/" +
            active +
            "/messages" +
            params({ page: selected, size: 50 }),
        );
    }
    setPage(selected);
    setMessages(saved.items);
    setTotal(saved.total);
  }
  useEffect(() => {
    if (conversationId)
      loadMessages(conversationId).catch((e) => setError(e.message));
  }, [conversationId]);
  useLayoutEffect(() => { following.current=true;setShowLatest(false); }, [conversationId, documentId]);
  useLayoutEffect(() => {
    if (following.current) jumpToLatest();
  }, [stream, messages, pending, toolRuns, toolsOpen]);
  async function send(e: FormEvent) {
    e.preventDefault();
    if (!text.trim() || pending || (documentId && currentPage && !currentPage.image)) return;
    const content = text;
    setText("");
    setPending(true);
    setStream("");
    setToolRuns([]);
    setToolsOpen(true);
    answering.current = false;
    setError("");
    let answer = "", frame: number | null = null;
    try {
      let active = id;
      if (!active) {
        const created = await api<Item>("/conversations", "POST", {
          title: content,
        });
        active = created.id;
        setId(active);
      }
      const contextRefs = [...(documentId && currentPage ? [{ type: "PAGE", page: currentPage.number, image: currentPage.image }] : []), ...selectedRefs];
      setMessages((m) => [
        ...m,
        { id: "sending", role: "USER", content, contextRefs },
      ]);
      await chat(active, { content, contextRefs }, (s) => {
        if (!answering.current && s.trim()) {setToolsOpen(false);answering.current=true;}
        answer += s;
        if (frame === null) frame = window.requestAnimationFrame(() => {setStream(answer);frame=null;});
      }, event => {
        if (event.status === "RUNNING" && answering.current) { answering.current=false;setToolsOpen(true); }
        setToolRuns(runs => runs.some(run => run.id === event.id) ? runs.map(run => run.id === event.id ? event : run) : [...runs,event]);
      });
      if (frame !== null) {window.cancelAnimationFrame(frame);frame=null;}
      setStream(answer);
      await loadMessages(active);
      setStream("");
      if (!documentId && !conversationId) navigate("/copilot/" + active);
      await qc.invalidateQueries({
        predicate: (q) => String(q.queryKey[0]).startsWith("/conversations"),
      });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      if (frame !== null) window.cancelAnimationFrame(frame);
      setPending(false);
    }
  }
  return (
    <section className="chat-panel">
      <div className="chat-heading">
        <div className="chat-title">
        {headerStart}
        <h1>
          Copilot
        </h1>
        </div>
        <div className="chat-header-actions">
        {headerActions}
        {documentId && id && (
          <button
            className="icon"
            aria-label="打开独立聊天页面"
            onClick={() => navigate("/copilot/" + id)}
          >
            <ArrowUpRight size={17} />
          </button>
        )}
        </div>
      </div>
      <div className="chat-history">
      <div className="chat-scroll" ref={scroll} tabIndex={0} aria-label="对话内容"
        onWheel={event => { if(event.deltaY<0){following.current=false;setShowLatest(true);} }}
        onScroll={event => {
          const view=event.currentTarget;
          const atBottom=view.scrollHeight-view.scrollTop-view.clientHeight<=8;
          following.current=atBottom;
          setShowLatest(!atBottom);
        }}>
        {messages.length === 0 && !pending ? (
          <div className="chat-empty">
            <div className="copilot-symbol">
              <Sparkles size={34} />
            </div>
            <h2>新建对话</h2>
          </div>
        ) : (
          <div className="messages">
            {messages.map((m) => (
              <article className={"message " + m.role.toLowerCase()} key={m.id}>
                {m.role === "ASSISTANT" && <ToolActivity calls={m.toolCalls || []} />}
                <div className="prose">
                  <Markdown>{m.content}</Markdown>
                </div>
                {m.contextRefs?.length > 0 && (
                  <div className="references">
                    {m.contextRefs.map((r: { type: string; id?: string; page?: number; image?: string; title?: string }, index: number) => {
                      if (r.type === "PAGE") return r.image ? (
                        <button key={index} className="reference-chip" title={`第 ${r.page} 页`} onClick={() => setAttachment({ page: r.page!, image: r.image! })}>
                          <Link2 size={12} /><span>第 {r.page} 页</span>
                        </button>
                      ) : <span key={index} className="reference-chip"><Link2 size={12} /><span>第 {r.page} 页</span></span>;
                      if (r.type === "DOCUMENT" || r.type === "COURSE") return (
                        <Link key={index} className="reference-chip" title={r.title || (r.type === "DOCUMENT" ? "文档" : "课程")} to={(r.type === "DOCUMENT" ? "/documents/" : "/courses/") + r.id}>
                          <Link2 size={12} /><span>{r.title || (r.type === "DOCUMENT" ? "文档" : "课程")}</span>
                        </Link>
                      );
                      if (r.type === "EVENT") return <Link key={index} className="reference-chip" title={r.title || "日程"} to="/calendar"><CalendarDays size={12} /><span>{r.title || "日程"}</span></Link>;
                      return null;
                    })}
                  </div>
                )}
              </article>
            ))}
            {pending && (
              <article className="message assistant">
                <ToolActivity calls={toolRuns} open={toolsOpen} onToggle={setToolsOpen} />
                <div className="prose">
                  {stream.trim() ? <Markdown>{stream}</Markdown> : <ThinkingIndicator />}
                </div>
              </article>
            )}
            {!pending && error && toolRuns.length > 0 && <article className="message assistant"><ToolActivity calls={toolRuns} open={toolsOpen} onToggle={setToolsOpen} /></article>}
          </div>
        )}
      </div>
      {showLatest && <button className="chat-jump-latest" aria-label="跳转到最新消息" title="跳转到最新消息" onClick={jumpToLatest}><ArrowDown size={18}/></button>}
      </div>
      {total > 50 && (
        <div className="pagination">
          <button
            className="icon"
            disabled={page === 0}
            onClick={() =>
              loadMessages(id!, page - 1).catch((e) => setError(e.message))
            }
            aria-label="上一页消息"
          >
            <ChevronLeft size={16} />
          </button>
          <span>{page + 1}</span>
          <button
            className="icon"
            disabled={(page + 1) * 50 >= total}
            onClick={() =>
              loadMessages(id!, page + 1).catch((e) => setError(e.message))
            }
            aria-label="下一页消息"
          >
            <ChevronRight size={16} />
          </button>
        </div>
      )}
      <form className="composer-wrap" onSubmit={send}>
        <ErrorText error={error} />
        <div className="composer">
          <textarea
            aria-label="发送消息"
            placeholder="给 Copilot 发消息…"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (
                e.key === "Enter" &&
                !e.shiftKey &&
                !e.nativeEvent.isComposing
              ) {
                e.preventDefault();
                e.currentTarget.form?.requestSubmit();
              }
            }}
            rows={2}
          />
          <div className="composer-bottom">
            <div className="attachment-control" ref={picker}>
              <button type="button" className="icon attach-add" aria-label="添加附件" aria-expanded={pickerOpen} aria-controls="attachment-picker" onClick={() => { setPickerOpen(!pickerOpen); setAttachmentQuery(""); }}><Plus size={21} /></button>
              {pickerOpen && <div className="attachment-picker" id="attachment-picker" role="dialog" aria-label="选择附件">
                <div className="attachment-search"><Search size={17} /><input autoFocus aria-label="搜索附件" placeholder="搜索课程、日程、文档" value={attachmentQuery} onChange={e => setAttachmentQuery(e.target.value)} /></div>
                <div className="attachment-results">
                  {groups.map(group => {
                    const matches = group.items.filter(v => `${v.title} ${v.code || ""} ${v.location || ""}`.toLowerCase().includes(attachmentQuery.trim().toLowerCase()));
                    const items = (attachmentQuery.trim() || group.type !== "EVENT" ? matches : matches.filter(v => new Date(v.endsAt || v.startsAt) >= new Date())).slice(0, attachmentQuery.trim() ? 20 : 3);
                    const Icon = group.icon;
                    return <section key={group.type}>
                      <h3>{group.label}</h3>
                      <ErrorText error={group.query.error} />
                      {group.query.isPending ? <p className="attachment-empty">加载中…</p> : items.length ? items.map(item => {
                        const selected = selectedRefs.some(r => r.type === group.type && r.id === item.id);
                        return <button type="button" key={item.id} className="attachment-option" aria-pressed={selected} onClick={() => toggleAttachment(group.type, item)}>
                          <Icon size={17} /><span>{item.title}</span>{selected && <Check size={16} />}
                        </button>;
                      }) : <p className="attachment-empty">{attachmentQuery ? "没有匹配项" : "暂无建议"}</p>}
                    </section>;
                  })}
                </div>
              </div>}
            </div>
            <div className="attachment-chips">
              {documentId && currentPage && <span className="attachment-chip"><FileText size={13} />第 {currentPage.number} 页</span>}
              {selectedRefs.slice(0, 2).map(ref => <span className="attachment-chip" key={ref.type + ref.id} title={ref.title}>
                <span>{ref.type === "COURSE" ? courses.data?.find(v => v.id === ref.id)?.customName || courses.data?.find(v => v.id === ref.id)?.name || ref.title : ref.title}</span>
                <button type="button" aria-label={"移除附件 " + ref.title} onClick={() => setSelectedRefs(refs => refs.filter(r => !(r.type === ref.type && r.id === ref.id)))}><X size={12} /></button>
              </span>)}
              {selectedRefs.length > 2 && <div className="attachment-summary">
                <button type="button" className="attachment-more" aria-label={"查看全部 " + selectedRefs.length + " 个附件"}>+{selectedRefs.length - 2}</button>
                <ul className="attachment-selected" aria-label="已选择的附件">
                  {selectedRefs.map(ref => <li key={ref.type + ref.id}>
                    <FileText size={15} /><span>{ref.title}</span>
                    <button type="button" aria-label={"移除附件 " + ref.title} onClick={() => setSelectedRefs(refs => refs.filter(r => !(r.type === ref.type && r.id === ref.id)))}><X size={13} /></button>
                  </li>)}
                </ul>
              </div>}
            </div>
            <button
              className="send"
              disabled={pending || !text.trim() || !!(documentId && currentPage && !currentPage.image)}
              aria-label="发送"
            >
              <ArrowUp size={19} />
            </button>
          </div>
        </div>
      </form>
      {attachment && <Modal title={"第 " + attachment.page + " 页"} close={() => setAttachment(null)}>
        <img src={attachment.image} alt={"附加的第 " + attachment.page + " 页"} style={{ display: "block", width: "100%", height: "auto" }} />
      </Modal>}
    </section>
  );
}
