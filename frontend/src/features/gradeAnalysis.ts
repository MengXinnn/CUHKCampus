import type { Item } from "../api";
export function isGradeSummary(column: Item) {
  return /^(weighted\s*total|total|加权总分|加权总计|总分|总计)$/i.test(column.name.trim());
}
export function gradeRate(column: Item): number | undefined {
  return !column.grade?.exempt && typeof column.grade?.score === "number" && column.score?.possible > 0
    ? column.grade.score / column.score.possible * 100 : undefined;
}
export function analyzeGrades(columns: Item[]) {
  const summaries=columns.filter(isGradeSummary).sort((a,b)=>Number(/weighted|加权/i.test(b.name))-Number(/weighted|加权/i.test(a.name)));
  const items=columns.filter(column=>!isGradeSummary(column));
  const graded=items.filter(column=>!column.grade?.exempt&&typeof column.grade?.score==="number");
  const pending=items.filter(column=>!column.grade?.exempt&&typeof column.grade?.score!=="number");
  const exempt=items.filter(column=>column.grade?.exempt);
  const rates=graded.map(gradeRate).filter((value):value is number=>value!==undefined);
  const bins=Array.from({length:5},(_,index)=>({label:`${index*20}–${(index+1)*20}%`,count:rates.filter(rate=>rate>=index*20&&(index===4?rate<=100:rate<(index+1)*20)).length}));
  const extra=rates.filter(rate=>rate>100).length;if(extra)bins.push({label:">100%",count:extra});
  const negative=rates.filter(rate=>rate<0).length;if(negative)bins.unshift({label:"<0%",count:negative});
  return {summaries,items,graded,pending,exempt,rates,bins,mean:rates.length?rates.reduce((sum,rate)=>sum+rate,0)/rates.length:undefined};
}
