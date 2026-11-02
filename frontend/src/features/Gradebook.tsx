import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, CircleDashed } from "lucide-react";
import { api, type Item } from "../api";
import { Empty, ErrorText } from "../components/ui";
import { analyzeGrades, gradeRate } from "./gradeAnalysis";
import "./gradebook.css";
const number=(value:number)=>value.toLocaleString("zh-CN",{maximumFractionDigits:1});
const score=(column:Item)=>column.grade?.exempt?"已豁免":typeof column.grade?.score==="number"?number(column.grade.score):column.grade?.displayGrade||"未评分";
export function Gradebook({courseId}:{courseId:string}) {
  const query=useQuery<Item[]>({queryKey:["/courses/"+courseId+"/grades"],queryFn:()=>api("/courses/"+courseId+"/grades")});
  const [filter,setFilter]=useState("all");
  const analysis=analyzeGrades(query.data||[]);
  const rows=filter==="graded"?analysis.graded:filter==="pending"?analysis.pending:analysis.items;
  return <div className="gradebook-view"><ErrorText error={query.error}/>
    {query.data?.length===0&&<Empty>暂无成绩项目</Empty>}
    {!!query.data?.length&&<>
      {!!analysis.summaries.length&&<div className="grade-summary-grid">{analysis.summaries.map((column,index)=>{
        const rate=gradeRate(column);
        return <section className={"grade-summary "+(index===0?"emphasis":"")} key={column.id}>
          <div className="grade-summary-heading"><h3>{column.name}</h3>{rate!==undefined&&<span>{number(rate)}%</span>}</div>
          <div className="grade-summary-score"><strong>{score(column)}</strong>{typeof column.score?.possible==="number"&&<span>/ {number(column.score.possible)}</span>}</div>
          {rate!==undefined&&<div className="grade-summary-meter"><span style={{width:`${Math.max(0,Math.min(100,rate))}%`}}/></div>}
        </section>;
      })}</div>}
      {!!analysis.items.length&&<>
        <div className="grade-overview">
          <div className="grade-overview-primary"><span>项目平均得分率</span><strong>{analysis.mean===undefined?"—":number(analysis.mean)+"%"}</strong><small>已评分项目等权平均</small></div>
          <div className="grade-overview-counts"><div><strong>{analysis.graded.length}<small>/ {analysis.items.length}</small></strong><span>已评分</span></div><div><strong>{analysis.pending.length}</strong><span>待评分</span></div>{analysis.exempt.length>0&&<div><strong>{analysis.exempt.length}</strong><span>已豁免</span></div>}</div>
        </div>
        {!!analysis.rates.length&&<section className="grade-distribution"><div className="grade-section-heading"><h3>得分分布</h3><span>最高 {number(Math.max(...analysis.rates))}% · 最低 {number(Math.min(...analysis.rates))}%</span></div>
          <div className="grade-chart" role="img" aria-label={"项目得分率分布："+analysis.bins.map(bin=>`${bin.label} ${bin.count}项`).join("，")}>
            {analysis.bins.map(bin=><div className="grade-chart-column" key={bin.label}><div className="grade-chart-track"><span className="grade-chart-count">{bin.count}</span><div className="grade-chart-bar" style={{height:`${bin.count/Math.max(...analysis.bins.map(b=>b.count),1)*60}px`}}/></div><span className="grade-chart-label">{bin.label}</span></div>)}
          </div>
        </section>}
        <section className="grade-item-section"><div className="grade-section-heading"><h3>评分明细</h3><div className="grade-filters" role="group" aria-label="筛选评分项目">{[["all","全部"],["graded","已评分"],["pending","待评分"]].map(([value,label])=><button key={value} aria-pressed={filter===value} onClick={()=>setFilter(value)}>{label}</button>)}</div></div>
          <div className="grade-item-list">{rows.map(column=>{
            const rate=gradeRate(column),graded=typeof column.grade?.score==="number"&&!column.grade?.exempt;
            return <article className="grade-item" key={column.id}>
              <span className={"grade-item-status "+(graded?"graded":"")} aria-hidden="true">{graded?<Check size={16}/>:<CircleDashed size={16}/>}</span>
              <div className="grade-item-main"><h4>{column.name}</h4>{rate!==undefined&&<div className="grade-item-meter" aria-label={`${column.name} 得分率 ${number(rate)}%`}><span style={{width:`${Math.max(0,Math.min(100,rate))}%`}}/></div>}</div>
              <div className="grade-item-score"><strong>{score(column)}</strong>{typeof column.score?.possible==="number"&&<span>满分 {number(column.score.possible)}</span>}</div>
              <span className="grade-item-percent">{rate===undefined?"—":number(rate)+"%"}</span>
            </article>;
          })}{!rows.length&&<Empty>暂无{filter==="pending"?"待评分":"已评分"}项目</Empty>}</div>
        </section>
      </>}
    </>}
  </div>;
}
