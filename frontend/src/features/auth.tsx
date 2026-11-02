import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { ErrorText, Field, Brand } from "../components/ui";
export function Auth() {
  const [register, setRegister] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const qc = useQueryClient();
  return (
    <div className="auth">
      <div className="auth-art">
        <Brand />
        <div>
          <h1>CUHKCampus</h1>
          <h1>一站式校园信息综合网站</h1>
        </div>
        <div className="auth-orbit" />
      </div>
      <form
        className="auth-form"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            await api(
              "/auth/" + (register ? "register" : "login"),
              "POST",
              Object.fromEntries(new FormData(e.currentTarget)),
            );
            await qc.invalidateQueries({ queryKey: ["me"] });
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <h2>{register ? "创建账号" : "欢迎回来"}</h2>
        {register && (
          <Field label="姓名">
            <input name="displayName" required autoComplete="name" />
          </Field>
        )}
        <Field label="邮箱">
          <input name="email" type="email" autoComplete="email" required />
        </Field>
        <Field label="密码">
          <input
            name="password"
            type="password"
            autoComplete={register ? "new-password" : "current-password"}
            required
            minLength={8}
          />
        </Field>
        <ErrorText error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "请稍候…" : register ? "注册" : "登录"}
        </button>
        <button
          type="button"
          className="text-button"
          onClick={() => {
            setRegister(!register);
            setError("");
          }}
        >
          {register ? "已有账号，登录" : "创建账号"}
        </button>
      </form>
    </div>
  );
}
