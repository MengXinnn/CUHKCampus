export type Transfer = { loaded: number; total: number };
export function FileProgress({ progress, label }: { progress: Transfer; label: string }) {
  const percent = progress.total > 0 ? Math.min(100, Math.floor(progress.loaded / progress.total * 100)) : undefined;
  return <div className="file-progress-panel" role="status">
    <div className="file-progress-copy"><span>{label}</span><strong>{percent === undefined ? `${(progress.loaded / 1048576).toFixed(1)} MB` : `${percent}%`}</strong></div>
    <progress aria-label={label} max={100} value={percent} />
  </div>;
}
