import { Download } from "lucide-react";
import type { DownloadProgress } from "../api";

export function DownloadIcon({ progress, label = "下载进度", size = 16 }: { progress?: DownloadProgress | null; label?: string; size?: number }) {
  const percent = progress && progress.total > 0 ? Math.min(100, progress.loaded / progress.total * 100) : undefined;
  return <span className="download-icon">
    <Download size={size}/>
    {progress && <svg className={"download-progress-ring"+(percent === undefined ? " is-indeterminate" : "")} viewBox="0 0 36 36" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent === undefined ? undefined : Math.floor(percent)}>
      <circle className="download-ring-track" cx="18" cy="18" r="16"/>
      <circle className="download-ring-value" cx="18" cy="18" r="16" pathLength="100" strokeDasharray="100" strokeDashoffset={100-(percent ?? 25)}/>
    </svg>}
  </span>;
}
