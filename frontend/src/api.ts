import { beginRequest } from "./requestProgress";
import { zip } from "fflate";
export type Item = { id: string; [key: string]: any };
export function csrf(): Record<string, string> {
  const value = document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.split("=")
    .slice(1)
    .join("=");
  return value ? { "X-XSRF-TOKEN": decodeURIComponent(value) } : {};
}
export async function api<T = any>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const generating = method === "POST" && (path.startsWith("/briefings") || /^\/mail\/[^/]+\/extract$/.test(path) || path === "/ai/test");
  const finish = generating ? undefined : beginRequest();
  try {
    const res = await fetch("/api/v1" + path, {
      method,
      credentials: "same-origin",
      headers: {
        ...csrf(),
        ...(body instanceof FormData
          ? {}
          : { "Content-Type": "application/json" }),
      },
      body:
        body === undefined
          ? undefined
          : body instanceof FormData
            ? body
            : JSON.stringify(body),
    });
    if (!res.ok) {
      const text = await res.text();
      let message = text;
      try {
        const error = JSON.parse(text);
        message = error.detail || error.message || error.error || text;
      } catch {}
      throw new Error(message || `请求失败 (${res.status})`);
    }
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  } finally { finish?.(); }
}
export function params(values: Record<string, unknown>) {
  return (
    "?" +
    new URLSearchParams(
      Object.entries(values)
        .filter(([, v]) => v !== "" && v !== undefined && v !== null)
        .map(([k, v]) => [k, String(v)]),
    ).toString()
  );
}
export async function chat(
  id: string,
  body: unknown,
  onDelta: (s: string) => void,
  onTool: (event: { id: string; name: string; status: string }) => void,
) {
  const res = await fetch(`/api/v1/conversations/${id}/messages`, {
    method: "POST",
    headers: { ...csrf(), "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  const reader = res.body!.getReader(),
    decoder = new TextDecoder();
  let buffer = "";
  let completed = false;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, "\n");
    let boundary;
    while ((boundary = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const event = frame
        .split("\n")
        .find((l) => l.startsWith("event:"))
        ?.slice(6)
        .trim();
      const data = frame
        .split("\n")
        .filter((l) => l.startsWith("data:"))
        .map((l) => l.slice(5).trim())
        .join("\n");
      if (!data) continue;
      const parsed = JSON.parse(data);
      if (event === "error")
        throw new Error(parsed.message || parsed.detail || String(parsed));
      if (event === "tool") onTool(parsed);
      if (event === "done") completed = true;
      if (event === "delta")
        onDelta(typeof parsed === "string" ? parsed : parsed.content);
    }
  }
  if (!completed) throw new Error("回答连接已中断，未收到完成事件");
}
export const date = (value?: string) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "未设置时间";
export const localDateTime = (value: string) =>
  value ? new Date(value).toISOString() : null;

export type DownloadProgress = { loaded: number; total: number };
async function readDownload(res: Response, onProgress?: (progress: DownloadProgress) => void, size = 0) {
  if (!res.ok) throw new Error(await res.text());
  const total=Number(res.headers.get("Content-Length")) || size;
  const reader=res.body!.getReader(), chunks: BlobPart[]=[];
  let loaded=0;
  onProgress?.({loaded,total});
  while(true){
    const {done,value}=await reader.read();
    if(done)break;
    chunks.push(value.slice().buffer);loaded+=value.byteLength;
    onProgress?.({loaded,total});
  }
  return new Uint8Array(await new Blob(chunks).arrayBuffer());
}
function saveDownloadBytes(bytes: Uint8Array, filename: string) {
  const url=URL.createObjectURL(new Blob([bytes.slice().buffer])), a=document.createElement("a");
  a.href=url;a.download=filename;document.body.append(a);a.click();a.remove();
  window.setTimeout(()=>URL.revokeObjectURL(url),60_000);
}
async function saveDownload(res: Response, filename: string, onProgress?: (progress: DownloadProgress) => void, size = 0) {
  saveDownloadBytes(await readDownload(res,onProgress,size),filename);
}
export async function downloadDocumentSelection(files: Item[], onProgress: (id: string, progress: DownloadProgress) => void) {
  const entries: Record<string,Uint8Array>={};
  // Reserve names before concurrent reads so duplicate filenames keep every selected file.
  const names=new Set<string>();
  const named=files.map(file=>{
    let name=file.displayName,index=2;
    while(names.has(name))name=`${file.displayName} (${index++})`;
    names.add(name);return {file,name};
  });
  await Promise.all(named.map(async({file,name})=>{
    onProgress(file.id,{loaded:0,total:file.byteSize});
    entries[name]=await readDownload(await fetch(`/api/v1/documents/${file.id}/file?download=true`),value=>onProgress(file.id,value),file.byteSize);
  }));
  const bytes=await new Promise<Uint8Array>((resolve,reject)=>zip(entries,{level:0},(error,data)=>error?reject(error):resolve(data)));
  saveDownloadBytes(bytes,"CUHKCampus-files.zip");
}
export async function downloadDocument(id: string, filename: string, size: number, onProgress: (progress: DownloadProgress) => void) {
  onProgress({loaded:0,total:size});
  await saveDownload(await fetch(`/api/v1/documents/${id}/file?download=true`),filename,onProgress,size);
}
export async function downloadFiles(ids: string[], folderIds: string[], onProgress?: (progress: DownloadProgress) => void) {
  const finish = beginRequest();
  try {
    const res = await fetch("/api/v1/documents/download", {
      method: "POST",
      headers: { ...csrf(), "Content-Type": "application/json" },
      body: JSON.stringify({ ids, folderIds }),
    });
    await saveDownload(res,"CUHKCampus-files.zip",onProgress);
  } finally { finish(); }
}
