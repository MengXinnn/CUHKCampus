import { useState, useEffect, useRef, type FormEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { UserRound, Sparkles, PlugZap, ChevronRight, Check, CircleAlert, ArrowUpRight, RefreshCw, Unplug, GraduationCap, Mail, Languages, Bot, Palette, List, X } from "lucide-react";
import { api, date, type Item } from "../api";
import { Field, PageHeader, useData } from "../components/ui";
import "./settings.css";

type Panel = "profile" | "models" | "connections";
type ModelConfig = { mode: string; baseUrl: string; model: string; apiKey?: string; hasKey?: boolean };
type Toast = { id: number; kind: "success" | "error"; message: string; closing?: boolean };
const colors = [
  { value: "#D6E0CF", name: "鼠尾草绿" }, { value: "#E3DAF5", name: "浅紫色" },
  { value: "#F4D2CB", name: "浅珊瑚色" }, { value: "#CAE5E1", name: "浅青色" },
  { value: "#F1F6A5", name: "淡黄绿色" }, { value: "#F6E0B9", name: "浅杏色" },
];
const panels = [
  { id: "profile" as const, label: "个人资料", icon: UserRound, color: "sage" },
  { id: "models" as const, label: "AI 模型", icon: Sparkles, color: "lilac" },
  { id: "connections" as const, label: "数据连接", icon: PlugZap, color: "peach" },
];
const timezones = ["Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Europe/London", "America/New_York", "America/Los_Angeles", "UTC"];

export function SettingsPage({ user }: { user: Item }) {
  const prefs = user.preferences || {};
  const [bbUsername,setBbUsername]=useState(""),[bbPassword,setBbPassword]=useState("");
  const [panel, setPanel] = useState<Panel>("profile");
  const [displayName, setDisplayName] = useState(user.displayName);
  const [avatarColor, setAvatarColor] = useState(prefs.avatarColor || colors[0].value);
  const [toasts, setToasts] = useState<Toast[]>([]), [busy, setBusy] = useState("");
  const toastTimers = useRef(new Map<number, number>()), nextToastId = useRef(0);
  const [agent, setAgent] = useState<ModelConfig>({ mode: prefs.aiMode || "API_KEY", baseUrl: prefs.aiBaseUrl || "", model: prefs.aiModel || "" });
  const [translation, setTranslation] = useState<ModelConfig>({ mode: prefs.translationAiMode || "API_KEY", baseUrl: prefs.translationAiBaseUrl || "", model: prefs.translationAiModel || "" });
  const connections = useData("/connections"), qc = useQueryClient();
  const bbAccount=useQuery<{username:string;passwordSaved:boolean}>({queryKey:["/connections/blackboard/account"],queryFn:()=>api("/connections/blackboard/account"),enabled:panel==="connections"});
  useEffect(()=>{if(bbAccount.data?.username)setBbUsername(current=>current||bbAccount.data!.username);},[bbAccount.data?.username]);
  const outlookConnected = connections.data?.some(c => c.provider === "OUTLOOK" && c.status === "CONNECTED") === true;
  const chatgpt = connections.data?.find(c => c.provider === "CHATGPT"), chatgptConnected = chatgpt?.status === "CONNECTED";
  const folders = useQuery<Item[]>({ queryKey: ["/connections/outlook/folders"], queryFn: () => api("/connections/outlook/folders"), enabled: outlookConnected && panel === "connections" });
  const catalog = useQuery<{ models: Item[] }>({ queryKey: ["chatgpt-models"], queryFn: () => api("/connections/chatgpt/models"), enabled: panel === "models" && chatgptConnected && (agent.mode === "CHATGPT" || translation.mode === "CHATGPT") });
  const plan = useQuery<{ connected: boolean; planEnabled: boolean; account: string }>({ queryKey: ["chatgpt-status"], queryFn: () => api("/connections/chatgpt/status"), enabled: panel === "models" && chatgptConnected });
  const aiConfig = useQuery<{ agent: ModelConfig; translation: ModelConfig }>({ queryKey: ["ai-config"], queryFn: () => api("/ai/config") });
  useEffect(() => {
    if (!aiConfig.data) return;
    setAgent({ ...aiConfig.data.agent, apiKey: "" });
    setTranslation({ ...aiConfig.data.translation, apiKey: "" });
  }, [aiConfig.data]);
  useEffect(() => {
    const refresh = () => qc.invalidateQueries({ queryKey: ["/connections"] });
    window.addEventListener("focus", refresh);
    return () => window.removeEventListener("focus", refresh);
  }, [qc]);
  useEffect(() => () => toastTimers.current.forEach(window.clearTimeout), []);
  useEffect(() => {
    const failure = connections.error || aiConfig.error || catalog.error || plan.error || folders.error;
    if (failure) notify("error", failure.message);
  }, [connections.error, aiConfig.error, catalog.error, plan.error, folders.error]);
  function notify(kind: Toast["kind"], message: string) {
    if (!message) return;
    const id = ++nextToastId.current;
    setToasts(list => [...list, { id, kind, message }]);
    toastTimers.current.set(id, window.setTimeout(() => closeToast(id), 3400));
  }
  function closeToast(id: number) {
    window.clearTimeout(toastTimers.current.get(id));
    toastTimers.current.delete(id);
    setToasts(list => list.map(item => item.id === id ? { ...item, closing: true } : item));
    if (!toastTimers.current.has(-id))
      toastTimers.current.set(-id, window.setTimeout(() => {
        toastTimers.current.delete(-id);
        setToasts(list => list.filter(item => item.id !== id));
      }, 200));
  }
  function clearToasts() {
    toastTimers.current.forEach(window.clearTimeout);
    toastTimers.current.clear();
    setToasts([]);
  }
  async function action<T>(name: string, operation: () => Promise<T>, message: string | ((result: T) => string) = "已保存", refresh = true) {
    setBusy(name);
    try {
      const result = await operation();
      notify("success", typeof message === "function" ? message(result) : message);
      if (refresh) await qc.invalidateQueries();
      return result;
    } catch (e) { notify("error", (e as Error).message); }
    finally { setBusy(""); }
  }
  function navigatePanel(next: Panel) { setPanel(next); clearToasts(); }
  function saveModel(kind: "agent" | "translation", config: ModelConfig) {
    return action(kind, async () => {
      const saved = await api<ModelConfig>("/ai/config", "POST", { kind, ...config });
      const next = { ...saved, apiKey: "" };
      if (kind === "agent") setAgent(next); else setTranslation(next);
      return saved;
    }, config.apiKey ? "模型与 API Key 已保存" : "模型配置已保存");
  }
  function testModel(kind: "agent" | "translation", config: ModelConfig) {
    return action(kind + "-test", () => api<{ ok: boolean; latencyMs: number }>("/ai/test", "POST", { kind, ...config }),
      result => `连接成功 (${result.latencyMs} ms)`, false);
  }
  function fetchModels(kind: "agent" | "translation", config: ModelConfig) {
    return action(kind + "-models", () => api<{ models: Item[] }>("/ai/models", "POST", { kind, ...config }),
      result => `已获取 ${result.models.length} 个模型`, false).then(result => result?.models || []);
  }
  async function connect(provider: string) {
    const { url } = await api<{ url: string }>("/connections/" + provider.toLowerCase() + "/authorize");
    window.location.assign(url);
  }
  return <>
    {!!toasts.length && createPortal(<div className="settings-toast-stack">
      {toasts.map(toast => <div key={toast.id} className={"settings-toast " + toast.kind + (toast.closing ? " closing" : "")} role={toast.kind === "error" ? "alert" : "status"}>
        {toast.kind === "success" ? <Check size={17} /> : <CircleAlert size={17} />}
        <span>{toast.message}</span>
        <button className="icon" aria-label="关闭通知" onClick={() => closeToast(toast.id)}><X size={15} /></button>
      </div>)}
    </div>, document.body)}
    <div className="settings-page">
    <PageHeader title="设置" />
    <div className="settings-shell">
      <aside className="settings-nav">
        <div className="settings-identity"><span className="settings-mini-avatar" style={{ background: avatarColor }}>{displayName?.slice(0, 1)}</span><strong>{displayName}</strong></div>
        <nav aria-label="设置分类">{panels.map(({ id, label, icon: Icon, color }) => <button key={id}
          className={"settings-nav-item " + (panel === id ? "selected " : "") + color} aria-current={panel === id ? "page" : undefined} onClick={() => navigatePanel(id)}>
          <span className="settings-nav-icon"><Icon size={19} /></span><span>{label}</span><ChevronRight size={15} />
        </button>)}</nav>
      </aside>
      <div className="settings-main">
        {panel === "profile" && <section className="settings-surface">
          <div className="settings-banner sage">
            <div className="settings-profile-avatar" style={{ background: avatarColor }}>{displayName?.slice(0, 1)}</div>
            <div><h2>{displayName}</h2><span>{user.email}</span></div><UserRound className="settings-banner-art" size={104} strokeWidth={1} />
          </div>
          <form className="settings-form" onSubmit={e => {
            e.preventDefault(); const fields = Object.fromEntries(new FormData(e.currentTarget));
            action("profile", () => api("/auth/me", "PATCH", { displayName, timezone: fields.timezone,
              preferences: { school: fields.school, college: fields.college, major: fields.major, studentNumber: fields.studentNumber, bio: fields.bio, avatarColor } }));
          }}>
            <div className="settings-section-heading"><h2>个人资料</h2><UserRound size={19} /></div>
            <div className="settings-fields">
              <Field label="姓名"><input value={displayName} onChange={e => setDisplayName(e.target.value)} required /></Field>
              <Field label="邮箱"><input type="email" value={user.email} readOnly /></Field>
              <Field label="学校"><input name="school" defaultValue={prefs.school || "香港中文大学（深圳）"} /></Field>
              <Field label="书院"><input name="college" defaultValue={prefs.college || ""} /></Field>
              <Field label="专业"><input name="major" defaultValue={prefs.major || ""} /></Field>
              <Field label="学号"><input name="studentNumber" defaultValue={prefs.studentNumber || ""} /></Field>
              <Field label="时区"><select name="timezone" defaultValue={user.timezone}>
                {!timezones.includes(user.timezone) && <option>{user.timezone}</option>}{timezones.map(t => <option key={t}>{t}</option>)}
              </select></Field>
              <div className="settings-palette"><span><Palette size={15} />头像颜色</span><div>{colors.map(color => <button key={color.value} type="button" style={{ background: color.value }}
                className={avatarColor === color.value ? "chosen" : ""} aria-label={color.name} aria-pressed={avatarColor === color.value} onClick={() => setAvatarColor(color.value)}>{avatarColor === color.value && <Check size={14} />}</button>)}</div></div>
              <div className="settings-full-field"><Field label="个人简介"><textarea name="bio" rows={3} defaultValue={prefs.bio || ""} /></Field></div>
            </div>
            <div className="settings-form-footer"><button className="primary" disabled={!!busy}>{busy === "profile" ? "保存中…" : "保存资料"}<Check size={16} /></button></div>
          </form>
        </section>}
        {panel === "models" && <div className="settings-models">
          <section className="settings-account">
            <span className="settings-provider-mark chatgpt"><Sparkles size={26} /></span>
            <div className="settings-account-copy"><h2>ChatGPT</h2>{chatgptConnected && <span>{chatgpt.accountIdentifier}</span>}</div>
            <span className={"settings-status " + (chatgptConnected ? "connected" : "")}><i />{chatgptConnected ? "已配置" : "未配置"}</span>
            {chatgptConnected ? <button className="secondary" disabled={!!busy} onClick={() => action("chatgpt-disconnect", () => api("/connections/chatgpt", "DELETE"), "已断开 ChatGPT")}><Unplug size={15} />断开</button>
              : <button className="primary" disabled={!!busy} onClick={() => action("chatgpt-connect", () => connect("CHATGPT"), "")}>Continue with ChatGPT<ArrowUpRight size={16} /></button>}
            {plan.data && !plan.data.planEnabled && <p className="settings-account-warning">尚未授权使用 ChatGPT 计划</p>}
          </section>
          <div className="settings-model-grid">
            <ModelEditor title="Agent" icon={<Bot size={25} />} color="lilac" config={agent} setConfig={setAgent} catalog={catalog.data?.models} connected={chatgptConnected}
              busy={!!busy} saving={busy === "agent"} testing={busy === "agent-test"} test={() => testModel("agent", agent)} fetchModels={() => fetchModels("agent", agent)} save={() => saveModel("agent", agent)} />
            <ModelEditor title="文档翻译" icon={<Languages size={25} />} color="peach" config={translation} setConfig={setTranslation} catalog={catalog.data?.models} connected={chatgptConnected}
              busy={!!busy} saving={busy === "translation"} testing={busy === "translation-test"} test={() => testModel("translation", translation)} fetchModels={() => fetchModels("translation", translation)} save={() => saveModel("translation", translation)} />
          </div>
        </div>}
        {panel === "connections" && <div className="settings-connections">
          <div className="settings-section-heading"><h2>数据连接</h2><button className="icon" aria-label="刷新连接状态" disabled={!!busy} onClick={() => action("refresh", () => qc.invalidateQueries({ queryKey: ["/connections"] }), "状态已刷新")}><RefreshCw size={17} /></button></div>
          {["BLACKBOARD", "OUTLOOK"].map(provider => {
            const connection = connections.data?.find(c => c.provider === provider), connected = connection?.status === "CONNECTED", expired = connection?.status === "LOGIN_REQUIRED";
            return <section className="settings-connection-card" key={provider}>
              <div className={"settings-connection-cover " + (provider === "BLACKBOARD" ? "lilac" : "sky")}><div className="settings-provider-heading"><span className="settings-provider-mark">{provider === "BLACKBOARD" ? <GraduationCap size={27} /> : <Mail size={27} />}</span><h2>{provider === "BLACKBOARD" ? "Blackboard" : "Outlook"}</h2></div>
                <span className={"settings-status " + (connected ? "connected" : "")}><i />{connected ? "已连接" : expired ? "会话已失效" : "未连接"}</span></div>
              <div className="settings-connection-body">
                {provider === "OUTLOOK" && connection?.lastSyncedAt && <span className="settings-last-sync">上次同步 {date(connection.lastSyncedAt)}</span>}

                {provider === "BLACKBOARD" && <form className="settings-outlook-config" onSubmit={e=>{e.preventDefault();action("bb-account",()=>api("/connections/blackboard/account","POST",{username:bbUsername,password:bbPassword}),"Blackboard 已连接");}}>
                  <Field label="学校账号"><input value={bbUsername} onChange={e=>setBbUsername(e.target.value)} autoComplete="username" required /></Field>
                  <Field label="密码"><input type="password" value={bbPassword} onChange={e=>setBbPassword(e.target.value)} autoComplete="current-password" placeholder={bbAccount.data?.passwordSaved?"••••••••":""} required={!bbAccount.data?.passwordSaved || bbUsername!==bbAccount.data?.username} /></Field>
                  <div className="settings-connection-actions">
                    {connected&&<button type="button" className="primary" disabled={!!busy} onClick={()=>action("sync-school",()=>api<Item>("/connections/blackboard/sync","POST"),result=>`已同步 ${result.courses} 门课程、${result.events} 节课`)}><RefreshCw size={15}/>{busy==="sync-school"?"同步中…":"同步"}</button>}{connected?<button type="button" className="secondary" disabled={!!busy} onClick={()=>action("disconnect-BLACKBOARD",async()=>{await api("/connections/blackboard","DELETE");setBbUsername("");setBbPassword("");},"已断开连接")}><Unplug size={15}/>断开</button>:<button className="primary" disabled={!!busy}>{busy==="bb-account"?"登录中…":"登录"}</button>}
                  </div>
                </form>}
                {provider === "OUTLOOK" && <div className="settings-connection-actions">{connected ? <>
                  {provider === "OUTLOOK" && <button className="primary" disabled={!!busy} onClick={() => action("sync-OUTLOOK", () => api<Item>("/connections/outlook/sync", "POST"), result => `已同步 ${result.synced} 封邮件`)}><RefreshCw size={15} />{busy === "sync-OUTLOOK" ? "同步中…" : "同步"}</button>}
                  <button className="secondary" disabled={!!busy} onClick={() => action("disconnect-" + provider, () => api("/connections/" + provider.toLowerCase(), "DELETE"), "已断开连接")}><Unplug size={15} />断开</button>
                </> : <button className="primary" disabled={!!busy} onClick={() => action("connect-OUTLOOK", () => connect("OUTLOOK"), "")}>{busy === "connect-OUTLOOK" ? "连接中…" : "Graph 登录"}<ArrowUpRight size={16} /></button>}</div>}
              </div>
            </section>;
          })}
          {outlookConnected && !!folders.data?.length && <section className="settings-folder-panel"><form onSubmit={e => {
            e.preventDefault(); const folderIds = new FormData(e.currentTarget).getAll("folderIds"); action("folders", () => api("/connections/outlook/folders", "PATCH", { folderIds }));
          }}><div className="settings-section-heading"><h2>同步文件夹</h2><Mail size={18} /></div>
            <div className="settings-folder-grid">{folders.data.map(folder => <label className="settings-folder" key={folder.id}><input type="checkbox" name="folderIds" value={folder.id} defaultChecked={folder.selected} /><span>{folder.displayName}</span></label>)}</div>
            <div className="settings-form-footer"><button className="primary" disabled={!!busy}>保存选择<Check size={16} /></button></div>
          </form></section>}
        </div>}
      </div>
    </div>
    </div>
  </>;
}

function ModelEditor({ title, icon, color, config, setConfig, catalog, connected, busy, saving, testing, test, fetchModels, save }: {
  title: string; icon: ReactNode; color: string; config: ModelConfig; setConfig: (v: ModelConfig) => void;
  catalog?: Item[]; connected: boolean; busy: boolean; saving: boolean; testing: boolean;
  test: () => Promise<unknown>; fetchModels: () => Promise<Item[]>; save: () => void;
}) {
  const [models, setModels] = useState<Item[]>([]), [loadingModels, setLoadingModels] = useState(false);
  const listId = "models-" + color;
  function submit(event: FormEvent) { event.preventDefault(); save(); }
  async function loadModels() {
    setLoadingModels(true);
    try { setModels(await fetchModels()); }
    finally { setLoadingModels(false); }
  }
  return <section className="settings-model-card">
    <div className={"settings-model-cover " + color}><span>{icon}</span><h2>{title}</h2></div>
    <form onSubmit={submit}>
      <div className="settings-mode-picker" role="group" aria-label={title + "调用方式"}>{[{ id: "API_KEY", name: "API Key" }, { id: "CHATGPT", name: "ChatGPT" }].map(mode => <button key={mode.id} type="button"
        className={config.mode === mode.id ? "selected" : ""} aria-pressed={config.mode === mode.id} onClick={() => { if(config.mode !== mode.id) setConfig({ ...config, mode: mode.id, model: "" }); }}>{mode.name}</button>)}</div>
      {config.mode === "API_KEY" ? <>
        <Field label="服务地址"><input type="url" required placeholder="https://api.openai.com/v1" value={config.baseUrl} onChange={e => setConfig({ ...config, baseUrl: e.target.value })} /></Field>
        <Field label="API Key"><input type="password" value={config.apiKey || ""} onChange={e => setConfig({ ...config, apiKey: e.target.value })}
          placeholder={config.hasKey ? "已配置，重新填写以更改" : "输入 API Key"} autoComplete="off" spellCheck={false} /></Field>
        <Field label="模型名称"><div className="settings-model-input">
          <input required list={listId} value={config.model} onChange={e => setConfig({ ...config, model: e.target.value })} />
          <button className="icon" type="button" aria-label="获取模型列表" title="获取模型列表" disabled={busy} onClick={loadModels}>
            <List size={17}/>
          </button>
          <datalist id={listId}>{models.map(model => <option key={model.id} value={model.id}>{model.name}</option>)}</datalist>
        </div></Field>
      </> : <Field label="模型"><select required value={config.model} disabled={!connected} onChange={e => setConfig({ ...config, model: e.target.value })}>
        <option value="">{connected ? "选择模型" : "请先连接 ChatGPT"}</option>{catalog?.map(model => <option key={model.id} value={model.id}>{model.name}</option>)}
      </select></Field>}
      <div className="settings-model-actions">
        {config.mode === "API_KEY" && <button className="secondary" type="button" disabled={busy} onClick={() => test()}>
          <PlugZap size={16} />{testing ? "测试中…" : "测试连接"}
        </button>}
        <button className="primary" disabled={busy || (config.mode === "CHATGPT" && !connected)}>{saving ? "保存中…" : "保存模型"}<Check size={16} /></button>
      </div>
    </form>
  </section>;
}
