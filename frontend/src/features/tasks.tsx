import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CalendarDays, Plus, Trash2, Star, Check } from "lucide-react";
import { api, params, date, localDateTime, type Item } from "../api";
import {
  Empty,
  ErrorText,
  useData,
  Field,
  PageHeader,
  FormModal,
  toInput,
} from "../components/ui";
import { taskTypes, statuses } from "../constants";
export function Tasks() {
  const [status, setStatus] = useState(""),
    [taskType, setType] = useState(""),
    [edit, setEdit] = useState<Item | null | undefined>(),
    [error, setError] = useState("");
  const data = useData("/tasks" + params({ status, taskType })),
    courses = useData("/courses"),
    qc = useQueryClient();
  async function patch(id: string, d: unknown) {
    try {
      await api("/tasks/" + id, "PATCH", d);
      qc.invalidateQueries();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <>
      <PageHeader title="任务与活动">
        <button className="primary" onClick={() => setEdit(null)}>
          <Plus size={17} />
          新建任务
        </button>
      </PageHeader>
      <div className="filters">
        <div className="segmented">
          {[
            ["", "全部"],
            ["TODO", "待办"],
            ["SUGGESTED", "待确认"],
            ["COMPLETED", "已完成"],
          ].map(([v, l]) => (
            <button
              className={status === v ? "active" : ""}
              key={v}
              onClick={() => setStatus(v)}
            >
              {l}
            </button>
          ))}
        </div>
        <select
          aria-label="任务类型"
          value={taskType}
          onChange={(e) => setType(e.target.value)}
        >
          <option value="">所有类型</option>
          {Object.entries(taskTypes).map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </div>
      <ErrorText error={data.error || error} />
      <section className="card task-list">
        {data.data?.map((t) => (
          <div
            className={
              "task-row " + (t.status === "COMPLETED" ? "completed" : "")
            }
            key={t.id}
          >
            <button
              className={
                "check-button " + (t.status === "COMPLETED" ? "checked" : "")
              }
              aria-label={t.status === "COMPLETED" ? "恢复待办" : "完成任务"}
              onClick={() =>
                patch(t.id, {
                  status: t.status === "COMPLETED" ? "TODO" : "COMPLETED",
                })
              }
            >
              {t.status === "COMPLETED" && <Check size={14} />}
            </button>
            <button className="task-info" onClick={() => setEdit(t)}>
              <strong>{t.title}</strong>
              <span>
                {taskTypes[t.taskType]} · {date(t.dueAt || t.startsAt)}
                {t.location && " · " + t.location}
              </span>
            </button>
            {t.status === "SUGGESTED" && (
              <button
                className="pill lime"
                onClick={() => patch(t.id, { status: "TODO" })}
              >
                确认
              </button>
            )}
            <button
              className={"icon " + (t.starred ? "starred" : "")}
              aria-label="收藏任务"
              onClick={() => patch(t.id, { starred: !t.starred })}
            >
              <Star size={17} fill={t.starred ? "currentColor" : "none"} />
            </button>
            <button
              className="icon"
              aria-label="加入日历"
              onClick={async () => {
                try {
                  await api("/tasks/" + t.id + "/calendar-event", "POST");
                  qc.invalidateQueries();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              <CalendarDays size={17} />
            </button>
            <button
              className="icon"
              aria-label="删除任务"
              onClick={async () => {
                await api("/tasks/" + t.id, "DELETE");
                qc.invalidateQueries();
              }}
            >
              <Trash2 size={17} />
            </button>
          </div>
        ))}
        {data.data?.length === 0 && <Empty>暂无任务，留些空间给新的计划。</Empty>}
      </section>
      {edit !== undefined && (
        <FormModal
          title={edit ? "编辑任务" : "新建任务"}
          close={() => setEdit(undefined)}
          submit={(d) =>
            api(
              edit ? "/tasks/" + edit.id : "/tasks",
              edit ? "PATCH" : "POST",
              {
                ...d,
                courseId: d.courseId || null,
                estimatedMinutes: d.estimatedMinutes
                  ? Number(d.estimatedMinutes)
                  : null,
                dueAt: localDateTime(d.dueAt),
                startsAt: localDateTime(d.startsAt),
                endsAt: localDateTime(d.endsAt),
              },
            )
          }
        >
          <Field label="标题">
            <input name="title" required defaultValue={edit?.title} />
          </Field>
          <div className="form-grid">
            <Field label="类型">
              <select
                name="taskType"
                defaultValue={edit?.taskType || "PERSONAL"}
              >
                {Object.entries(taskTypes).map(([v, l]) => (
                  <option key={v} value={v}>
                    {l}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="状态">
              <select name="status" defaultValue={edit?.status || "TODO"}>
                {Object.entries(statuses).map(([v, l]) => (
                  <option key={v} value={v}>
                    {l}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="优先级">
              <select name="priority" defaultValue={edit?.priority || "NORMAL"}>
                <option value="NORMAL">普通</option>
                <option value="HIGH">高</option>
                <option value="LOW">低</option>
              </select>
            </Field>
            <Field label="课程">
              <select name="courseId" defaultValue={edit?.courseId || ""}>
                <option value="">无</option>
                {courses.data?.map((c) => (
                  <option value={c.id} key={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="说明">
            <textarea name="description" defaultValue={edit?.description} />
          </Field>
          <div className="form-grid">
            <Field label="截止时间">
              <input
                type="datetime-local"
                name="dueAt"
                defaultValue={toInput(edit?.dueAt)}
              />
            </Field>
            <Field label="预计分钟">
              <input
                type="number"
                min="1"
                name="estimatedMinutes"
                defaultValue={edit?.estimatedMinutes}
              />
            </Field>
            <Field label="开始时间">
              <input
                type="datetime-local"
                name="startsAt"
                defaultValue={toInput(edit?.startsAt)}
              />
            </Field>
            <Field label="结束时间">
              <input
                type="datetime-local"
                name="endsAt"
                defaultValue={toInput(edit?.endsAt)}
              />
            </Field>
          </div>
          <Field label="地点">
            <input name="location" defaultValue={edit?.location} />
          </Field>
          <Field label="主办方">
            <input name="organizer" defaultValue={edit?.organizer} />
          </Field>
          <Field label="活动分类">
            <input name="category" defaultValue={edit?.category} />
          </Field>
          <Field label="报名链接">
            <input
              type="url"
              name="registrationUrl"
              defaultValue={edit?.registrationUrl}
            />
          </Field>
        </FormModal>
      )}
    </>
  );
}
