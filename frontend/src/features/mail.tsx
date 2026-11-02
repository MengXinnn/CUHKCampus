import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Sparkles, Download, Search } from "lucide-react";
import { api, params, date, type Item } from "../api";
import {
  Empty,
  ErrorText,
  useData,
  Modal,
  SearchBox,
  PageHeader,
} from "../components/ui";
export function MailPage() {
  const [q, setQ] = useState(""),
    [category, setCategory] = useState(""),
    [selected, setSelected] = useState<Item | null>(null),
    [error, setError] = useState(""),
    [remoteResults, setRemoteResults] = useState<Item[] | null>(null),
    [searching, setSearching] = useState(false);
  const data = useData("/mail" + params({ q, category })),
    documents = useData("/documents"),
    qc = useQueryClient();
  const messages = remoteResults === null ? data.data : remoteResults;
  async function searchOutlook() {
    setSearching(true); setError("");
    try { setRemoteResults(await api<Item[]>("/mail/search" + params({ query: q, limit: 50 }))); }
    catch (error) { setError((error as Error).message); }
    finally { setSearching(false); }
  }
  return (
    <>
      <PageHeader title="邮件中心">
        <SearchBox value={q} onChange={value => { setQ(value); setRemoteResults(null); setError(""); }} placeholder="搜索邮件" />
        <button className="secondary" disabled={searching || !q.trim()} onClick={searchOutlook}><Search size={16} />{searching ? "搜索中…" : "搜索 Outlook"}</button>
        {remoteResults !== null && <button className="text-button" onClick={() => setRemoteResults(null)}>返回已同步邮件</button>}
        <select
          aria-label="邮件分类"
          value={category}
          onChange={(e) => { setCategory(e.target.value); setRemoteResults(null); }}
        >
          <option value="">全部邮件</option>
          {Object.entries({
            ACADEMIC: "课程",
            ADMINISTRATIVE: "行政",
            COLLEGE: "书院",
            ACTIVITY: "活动",
            GENERAL: "其他",
          }).map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </PageHeader>
      <ErrorText error={data.error || error} />
      <section className="card mail-list">
        {messages?.map((m) => (
          <button
            className={"mail-row " + (!m.isRead ? "unread" : "")}
            key={m.id}
            onClick={async () => {
              try {
                const detail = await api("/mail/" + m.id);
                setSelected(detail);
                await api("/mail/" + m.id, "PATCH", { isRead: true });
                setRemoteResults(items => items?.map(item => item.id === m.id ? { ...item, isRead: true } : item) ?? null);
                qc.invalidateQueries();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <span className="mail-dot" />
            <div>
              <strong>{m.subject}</strong>
              <span>
                {m.sender?.name ||
                  m.sender?.emailAddress?.name ||
                  m.sender?.address}
              </span>
              <p>{m.summary}</p>
            </div>
            <time>{date(m.receivedAt)}</time>
          </button>
        ))}
        {messages?.length === 0 && (
          <Empty>{remoteResults !== null ? "Outlook 中没有匹配的邮件" : q ? "没有匹配的已同步邮件" : "暂无邮件，在设置中连接 Outlook。"}</Empty>
        )}
      </section>
      {selected && (
        <Modal title={selected.subject} close={() => setSelected(null)}>
          <p className="muted">{date(selected.receivedAt)}</p>
          <div className="mail-body preserve">{selected.bodyText}</div>
          {documents.data
            ?.filter((d) => d.mailMessageId === selected.id)
            .map((d: Item) => (
              <Link className="list-row" key={d.id} to={"/documents/" + d.id}>
                {d.displayName}
                <Download size={16} />
              </Link>
            ))}
          <button
            className="primary"
            onClick={async () => {
              try {
                await api("/mail/" + selected.id + "/extract", "POST");
                qc.invalidateQueries();
                setSelected(null);
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <Sparkles size={16} />
            提取任务与活动
          </button>
        </Modal>
      )}
    </>
  );
}
