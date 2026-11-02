import { useEffect, useState, type ReactNode } from "react";
import { ErrorText } from "../components/ui";
import { PdfPreview } from "./PdfPreview";

export function SlidePreview({ id, translation, onPage }: {
  id: string; translation?: ReactNode; onPage: (page: number, text: string, image: string) => void;
}) {
  const [pages, setPages] = useState(0), [ready, setReady] = useState<number[]>([]),
    [done, setDone] = useState(false), [loaded, setLoaded] = useState(0), [error, setError] = useState("");
  const base = "/api/v1/documents/" + id;
  useEffect(() => {
    setPages(0);setReady([]);setDone(false);setLoaded(0);setError("");
    const source = new EventSource(base + "/slides/stream");
    source.addEventListener("info", event => setPages(JSON.parse(event.data).pages));
    source.addEventListener("page", event => {
      const page = JSON.parse(event.data).page;
      setReady(pages => pages.includes(page) ? pages : [...pages,page]);
    });
    source.addEventListener("done", () => {source.close();setDone(true);});
    source.addEventListener("failed", event => {source.close();setError(JSON.parse(event.data).message);});
    source.onerror = () => {source.close();setError("预览生成连接已中断");};
    return () => source.close();
  }, [base]);
  return <div className="slide-stream-reader">
    <ErrorText error={error} />
    {pages > 0 && <PdfPreview url={base + "/preview"} pageCount={pages} availablePages={ready}
      thumbnailUrl={base + "/slides/thumbnail"} onLoadedPages={setLoaded} translation={translation} onPage={onPage} />}
  </div>;
}
