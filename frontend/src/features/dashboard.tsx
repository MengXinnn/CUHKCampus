import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Sparkles, Plus, ArrowUpRight } from "lucide-react";
import { api, params, date, type Item } from "../api";
import { Markdown, Empty, ErrorText, useData } from "../components/ui";
export function Dashboard({ user }: { user: Item }) {
  const tasks = useData("/tasks"),
    courses = useData("/courses"),
    mail = useData("/mail?isRead=false");
  const [announcementCourse, setAnnouncementCourse] = useState("");
  const ann = useQuery<Item[]>({
    queryKey: ["/announcements?courseId=" + announcementCourse],
    queryFn: () => api("/announcements?courseId=" + announcementCourse),
    enabled: !!announcementCourse,
  });
  const today = new Date().toISOString().slice(0, 10),
    events = useData(
      "/calendar" +
        params({
          from: today + "T00:00:00+08:00",
          to: today + "T23:59:59+08:00",
        }),
    );
  const briefing = useData<Item>("/briefings?date=" + today);
  const qc = useQueryClient(),
    [error, setError] = useState(""),
    [generating, setGenerating] = useState(false);
  const active = (tasks.data || []).filter((t) => t.status === "TODO");
  return (
    <>
      <section className="hero">
        <div className="hero-copy">
          <span className="eyebrow">CUHKSZ</span>
          <h1>
            你好{user.displayName}，<br />
            看看今天有什么新鲜事？
          </h1>
          <Link className="hero-action" to="/copilot">
            和 Copilot 聊聊 <ArrowUpRight size={18} />
          </Link>
        </div>
        <div className="hero-sculpture">
          <div />
          <div />
          <div />
          <div />
        </div>
        <div className="stats">
          {[
            [courses.data?.length, "我的课程", "sage"],
            [active.length, "待办事项", "lavender"],
            [mail.data?.length, "未读邮件", "blue"],
          ].map(([n, l, c]) => (
            <div key={String(l)} className={"stat " + c}>
              <span>{l}</span>
              <strong>{n ?? "—"}</strong>
            </div>
          ))}
        </div>
      </section>
      <div className="dashboard-grid">
        <section className="card today">
          <div className="section-head">
            <h2>今日安排</h2>
            <Link to="/calendar" className="round-link" aria-label="查看日历">
              <ArrowUpRight size={18} />
            </Link>
          </div>
          {events.isError ? (
            <ErrorText error={events.error} />
          ) : events.data?.length ? (
            <div className="timeline">
              {events.data.map((e) => (
                <div key={e.id} className="timeline-row">
                  <time>
                    {new Date(e.startsAt).toLocaleTimeString("zh-CN", {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </time>
                  <div>
                    <strong>{e.title}</strong>
                    <span>{e.location}</span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <Empty>今天暂无日程</Empty>
          )}
          <Link className="text-link" to="/calendar">
            安排时间 <Plus size={15} />
          </Link>
        </section>
        <section className="card upcoming">
          <div className="section-head">
            <h2>近期截止与活动</h2>
            <Link to="/tasks" className="pill">
              查看全部
            </Link>
          </div>
          {active.length ? (
            active
              .sort((a, b) => (a.dueAt || "").localeCompare(b.dueAt || ""))
              .slice(0, 6)
              .map((t) => (
                <Link to="/tasks" className="event-row" key={t.id}>
                  <span className="muted">{date(t.dueAt || t.startsAt)}</span>
                  <strong>{t.title}</strong>
                  <ArrowUpRight size={16} />
                </Link>
              ))
          ) : (
            <Empty>所有事情都已安排妥当</Empty>
          )}
        </section>
        <section className="card briefing">
          <div className="section-head">
            <h2>今日简报</h2>
            <button
              className="icon"
              aria-label="生成今日简报"
              disabled={generating}
              onClick={async () => {
                setGenerating(true);
                setError("");
                try {
                  await api("/briefings?date=" + today, "POST");
                  qc.invalidateQueries({
                    queryKey: ["/briefings?date=" + today],
                  });
                } catch (e) {
                  setError((e as Error).message);
                } finally {
                  setGenerating(false);
                }
              }}
            >
              <Sparkles size={18} />
            </button>
          </div>
          {generating ? (
            <Empty>正在整理…</Empty>
          ) : briefing.data?.contentMarkdown ? (
            <div className="prose">
              <Markdown>{briefing.data.contentMarkdown}</Markdown>
            </div>
          ) : (
            <Empty>
              点击 <Sparkles size={16} /> 生成今天的简报
            </Empty>
          )}
          <ErrorText error={error} />
        </section>
        <section className="card notices">
          <div className="section-head">
            <h2>课程公告</h2>
            <select aria-label="公告课程" value={announcementCourse} onChange={e => setAnnouncementCourse(e.target.value)}>
              <option value="">选择课程</option>
              {courses.data?.map(c => <option key={c.id} value={c.id}>{c.customName || c.name}</option>)}
            </select>
          </div>
          <ErrorText error={ann.error || courses.error} />
          {ann.data?.length ? (
            ann.data.slice(0, 4).map((a) => (
              <Link
                to={"/courses/" + a.courseId}
                className="notice-row"
                key={a.id}
              >
                <strong>{a.title}</strong>
                <span>{date(a.publishedAt)}</span>
              </Link>
            ))
          ) : (
            announcementCourse && !ann.isPending && !ann.error ? <Empty>暂无课程公告</Empty> : null
          )}
        </section>
      </div>
    </>
  );
}
