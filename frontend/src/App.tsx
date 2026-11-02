import { useState, useEffect } from "react";
import { Routes, Route, NavLink, Link, useLocation } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  House,
  GraduationCap,
  Files,
  Mail,
  CheckCheck,
  CalendarDays,
  Sparkles,
  Settings,
  LogOut,
  Menu,
} from "lucide-react";
import { api, date, type Item } from "./api";
import { Empty, Brand } from "./components/ui";
import { Auth } from "./features/auth";
import { Dashboard } from "./features/dashboard";
import { Courses, CourseDetail } from "./features/courses";
import { Documents, DocumentView } from "./features/documents";
import { MailPage } from "./features/mail";
import { Tasks } from "./features/tasks";
import { CalendarPage } from "./features/calendar";
import { Copilot } from "./features/copilot";
import { SettingsPage } from "./features/settings";
const nav = [
  ["/", "校园总览", House],
  ["/courses", "课程中心", GraduationCap],
  ["/documents", "文档库", Files],
  ["/mail", "邮件中心", Mail],
  ["/tasks", "任务与活动", CheckCheck],
  ["/calendar", "日历与规划", CalendarDays],
  ["/copilot", "Copilot", Sparkles],
] as const;
export default function App() {
  const me = useQuery<Item>({
    queryKey: ["me"],
    queryFn: () => api("/auth/me"),
  });
  const [menu, setMenu] = useState(false);
  const qc = useQueryClient();
  const location = useLocation();
  useEffect(() => setMenu(false), [location.pathname]);
  if (me.isPending)
    return (
      <div className="app-loading">
        <Brand />
      </div>
    );
  if (!me.data) return <Auth />;
  const isReader = location.pathname.startsWith("/documents/");
  const isCopilot = location.pathname.startsWith("/copilot");
  const isHome = location.pathname === "/";
  return (
    <div className="app">
      <button
        className="mobile-menu icon"
        aria-label="打开菜单"
        onClick={() => setMenu(!menu)}
      >
        <Menu />
      </button>
      <aside className={"rail " + (menu ? "open" : "")}>
        <Link to="/" className="logo">
          <Brand />
        </Link>
        <nav>
          {nav.map(([path, label, Icon]) => (
            <NavLink
              key={path}
              to={path}
              end={path === "/"}
              title={label}
              aria-label={label}
            >
              <Icon size={21} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="rail-bottom">
          <NavLink to="/settings" title="设置" aria-label="设置">
            <Settings size={21} />
            <span>设置</span>
          </NavLink>
          <button
            className="rail-link"
            title="退出登录"
            aria-label="退出登录"
            onClick={async () => {
              await api("/auth/logout", "POST");
              qc.clear();
              window.location.assign("/");
            }}
          >
            <LogOut size={21} />
            <span>退出登录</span>
          </button>
        </div>
      </aside>
      <main className={"workspace" + (isReader ? " is-reader" : "") + (isCopilot ? " is-copilot" : "")}>
        {isHome && <div className="topbar">
          <Link to="/" className="wordmark">
            CUHK <span>Campus</span>
          </Link>
          <span className="top-date">
            {new Date().toLocaleDateString("zh-CN", {
              month: "long",
              day: "numeric",
              weekday: "long",
            })}
          </span>
          <Link to="/settings" className="avatar" title={me.data.displayName} style={{ background: me.data.preferences?.avatarColor || "#d6e0cf" }}>
            {me.data.displayName?.slice(0, 1)}
          </Link>
        </div>}
        <div className="route-view" key={location.pathname}>
        <Routes>
          <Route path="/" element={<Dashboard user={me.data} />} />
          <Route path="/courses" element={<Courses />} />
          <Route path="/courses/:id" element={<CourseDetail />} />
          <Route path="/documents" element={<Documents />} />
          <Route path="/documents/:id" element={<DocumentView key={location.pathname} />} />
          <Route path="/mail" element={<MailPage />} />
          <Route path="/tasks" element={<Tasks />} />
          <Route path="/calendar" element={<CalendarPage />} />
          <Route path="/copilot" element={<Copilot />} />
          <Route path="/copilot/:conversationId" element={<Copilot />} />
          <Route path="/settings" element={<SettingsPage user={me.data} />} />
          <Route
            path="*"
            element={
              <Empty>
                页面不存在 <Link to="/">返回主页</Link>
              </Empty>
            }
          />
        </Routes>
        </div>
      </main>
    </div>
  );
}
