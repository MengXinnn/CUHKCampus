import "./calendar.css";
import { useState, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Sparkles, Plus, ArrowUpRight, CalendarPlus, ChevronLeft, ChevronRight } from "lucide-react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import zhLocale from "@fullcalendar/core/locales/zh-cn";
import { api, params, localDateTime, type Item } from "../api";
import {
  ErrorText,
  useData,
  Modal,
  Field,
  PageHeader,
  FormModal,
  toInput,
} from "../components/ui";
export function CalendarPage() {
  const calendar=useRef<FullCalendar>(null);
  const [calendarTitle,setCalendarTitle]=useState(""),[view,setView]=useState("timeGridWeek");
  const [range, setRange] = useState({
      from: new Date().toISOString(),
      to: new Date(Date.now() + 30 * 864e5).toISOString(),
    }),
    [create, setCreate] = useState<Item | null | undefined>(),
    [plan, setPlan] = useState(false),
    [error, setError] = useState(""),
    [outlook, setOutlook] = useState<{ id: string; webLink: string }>(),
    [outlookError, setOutlookError] = useState(""),
    [outlookBusy, setOutlookBusy] = useState(false);
  function openEvent(event: Item | null | undefined) {
    setCreate(event);
    setOutlook(undefined);
    setOutlookError("");
  }
  const data = useData("/calendar" + params(range)),
    tasks = useData("/tasks?status=TODO"),
    qc = useQueryClient();
  return (
    <>
      <PageHeader title="课表与日历">
        <button className="secondary" onClick={() => setPlan(true)}>
          <Sparkles size={16} />
          规划学习
        </button>
        <button className="primary" onClick={() => openEvent(null)}>
          <Plus size={17} />
          添加日程
        </button>
      </PageHeader>
      <ErrorText error={data.error || error} />
      <section className="card calendar-card campus-calendar">
        <div className="calendar-toolbar">
          <div className="calendar-navigation">
            <h2>{calendarTitle}</h2>
            <div className="calendar-date-actions">
              <button className="icon" aria-label="上一段日期" onClick={()=>calendar.current!.getApi().prev()}><ChevronLeft size={17}/></button>
              <button className="calendar-today" onClick={()=>calendar.current!.getApi().today()}>今天</button>
              <button className="icon" aria-label="下一段日期" onClick={()=>calendar.current!.getApi().next()}><ChevronRight size={17}/></button>
            </div>
          </div>
          <div className="calendar-views" role="group" aria-label="日历视图">
            {[["timeGridWeek","周"],["dayGridMonth","月"],["timeGridDay","日"]].map(([value,label])=><button key={value} aria-pressed={view===value} className={view===value?"active":""} onClick={()=>calendar.current!.getApi().changeView(value)}>{label}</button>)}
          </div>
        </div>
        <div className="calendar-grid">
        <FullCalendar
          ref={calendar}
          plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
          locale={zhLocale}
          initialView="timeGridWeek"
          headerToolbar={false}
          firstDay={1}
          height="100%"
          slotMinTime="08:00:00"
          slotMaxTime="23:00:00"
          scrollTime="08:00:00"
          slotDuration="00:30:00"
          slotLabelInterval="01:00:00"
          slotLabelFormat={{hour:"2-digit",minute:"2-digit",hour12:false}}
          eventTimeFormat={{hour:"2-digit",minute:"2-digit",hour12:false}}
          allDayText="全天"
          allDaySlot={data.data?.some(event=>event.allDay)??false}
          nowIndicator
          slotEventOverlap={false}
          dayMaxEvents={3}
          dayHeaderContent={info=><div className="calendar-day-heading"><span>{info.date.toLocaleDateString("zh-CN",{weekday:"short"})}</span>{info.view.type!=="dayGridMonth"&&<b>{info.date.getDate()}</b>}</div>}
          datesSet={(info) => {
            setRange({ from: info.startStr, to: info.endStr });setCalendarTitle(info.view.title);setView(info.view.type);
          }}
          events={data.data?.map((e) => ({
            id: e.id,
            title: e.title,
            start: e.startsAt,
            end: e.endsAt,
            allDay: e.allDay,
            classNames:["campus-event","event-tone-"+(Math.abs([...String(e.title).split(" - ")[0]].reduce((hash,char)=>hash*31+char.charCodeAt(0)|0,0))%6)],
            extendedProps: { record: e },
          }))}
          eventContent={info=>{
            const record=info.event.extendedProps.record;
            const course=record.eventType==="SIS"||record.eventType==="CLASS";
            const parts=info.event.title.split(" - "),section=info.event.title.split(" · ")[1];
            return <div className="calendar-event-content" title={[info.event.title,info.timeText,record.location].filter(Boolean).join("\n")}>
              <strong>{course?parts[0]:info.event.title}{section&&<span>{section}</span>}</strong>
              <span className="calendar-event-time">{info.timeText}</span>
              {record.location&&<span className="calendar-event-location">{record.location}</span>}
            </div>;
          }}
          dateClick={(info) => {
            const start=new Date(info.date);if(info.allDay)start.setHours(9,0,0,0);
            openEvent({id:"",startsAt:start.toISOString(),endsAt:new Date(start.getTime()+3600000).toISOString()});
          }}
          eventClick={(info) => {
            if (info.event.extendedProps.record.eventType !== "DEADLINE")
              openEvent(info.event.extendedProps.record);
          }}
        />
        </div>
      </section>
      {create !== undefined && (
        <FormModal
          title={create?.id ? "编辑日程" : "添加日程"}
          close={() => openEvent(undefined)}
          submit={(d) =>
            api(
              "/calendar/events" + (create?.id ? "/" + create.id : ""),
              create?.id ? "PATCH" : "POST",
              {
                ...d,
                startsAt: localDateTime(d.startsAt),
                endsAt: localDateTime(d.endsAt),
              },
            )
          }
        >
          <Field label="标题">
            <input name="title" defaultValue={create?.title} required />
          </Field>
          <Field label="类型">
            <select
              name="eventType"
              defaultValue={create?.eventType || "PERSONAL"}
            >
              {Object.entries({
                PERSONAL: "个人安排",
                CLASS: "课程",
                EXAM: "考试",
                ACTIVITY: "活动",
                STUDY: "学习",
              }).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </Field>
          <Field label="开始">
            <input
              type="datetime-local"
              name="startsAt"
              defaultValue={toInput(create?.startsAt)}
              required
            />
          </Field>
          <Field label="结束">
            <input
              type="datetime-local"
              name="endsAt"
              defaultValue={toInput(create?.endsAt)}
              required
            />
          </Field>
          <Field label="地点">
            <input name="location" defaultValue={create?.location} />
          </Field>
          {create?.id && create.eventType !== "DEADLINE" && <div>
            <ErrorText error={outlookError} />
            {outlook ? <p className="notice" role="status">已添加到 Outlook · <a href={outlook.webLink} target="_blank" rel="noreferrer">查看日程 <ArrowUpRight size={13} style={{ display: "inline" }} /></a></p>
              : <button type="button" className="secondary" disabled={outlookBusy} onClick={async () => {
                setOutlookBusy(true); setOutlookError("");
                try { setOutlook(await api<{ id: string; webLink: string }>("/calendar/events/" + create.id + "/outlook", "POST")); }
                catch (error) { setOutlookError((error as Error).message); }
                finally { setOutlookBusy(false); }
              }}><CalendarPlus size={16} />{outlookBusy ? "添加中…" : "添加到 Outlook"}</button>}
          </div>}
          {create?.id && (
            <button
              type="button"
              className="danger text-button"
              onClick={async () => {
                await api("/calendar/events/" + create.id, "DELETE");
                qc.invalidateQueries();
                openEvent(undefined);
              }}
            >
              删除日程
            </button>
          )}
        </FormModal>
      )}
      {plan && (
        <Modal title="规划学习" close={() => setPlan(false)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const fd = new FormData(e.currentTarget);
              try {
                await api("/calendar/study-plan", "POST", {
                  taskIds: fd.getAll("taskIds"),
                  from: localDateTime(String(fd.get("from"))),
                  to: localDateTime(String(fd.get("to"))),
                });
                qc.invalidateQueries();
                setPlan(false);
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            {tasks.data?.map((t) => (
              <label className="checkbox-line" key={t.id}>
                <input type="checkbox" name="taskIds" value={t.id} />
                {t.title}{" "}
                <span className="muted">{t.estimatedMinutes || 0} 分钟</span>
              </label>
            ))}
            <Field label="可学习时间开始">
              <input name="from" type="datetime-local" required />
            </Field>
            <Field label="可学习时间结束">
              <input name="to" type="datetime-local" required />
            </Field>
            <button className="primary">生成学习时段</button>
          </form>
        </Modal>
      )}
    </>
  );
}
