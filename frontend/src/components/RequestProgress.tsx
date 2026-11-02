import { useSyncExternalStore } from "react";
import { useLocation } from "react-router-dom";
import { requestProgress } from "../requestProgress";

export function RequestProgress() {
  const pending = useSyncExternalStore(requestProgress.subscribe, requestProgress.snapshot);
  const { pathname } = useLocation();
  const hidden = pathname.startsWith("/copilot") || pathname.startsWith("/documents/");
  return pending > 0 && !hidden ? (
    <div className="request-progress" role="progressbar" aria-label="正在加载" aria-valuetext="请求进行中">
      <span />
    </div>
  ) : null;
}
