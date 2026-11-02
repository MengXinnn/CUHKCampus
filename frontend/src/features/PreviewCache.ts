import { getDocument, PDFWorker, type PDFDocumentProxy, type PDFDocumentLoadingTask } from "pdfjs-dist";
import type { Transfer } from "../components/FileProgress";

type Entry = { task: PDFDocumentLoadingTask; promise: Promise<PDFDocumentProxy>; document?: PDFDocumentProxy };
export class PreviewCache {
  private worker?: PDFWorker;
  private entries = new Map<string, Entry>();
  constructor(private onReady?: (count: number) => void) {}
  peek(url: string) { return this.entries.get(url)?.document; }
  load(url: string, onProgress?: (progress: Transfer) => void) {
    let entry = this.entries.get(url);
    if (!entry) {
      this.worker ??= new PDFWorker();
      const task = getDocument({ url, worker: this.worker, enableHWA: true });
      entry = { task, promise: task.promise.then(async document => {
        await (await document.getPage(1)).getOperatorList();
        entry!.document = document;
        this.onReady?.([...this.entries.values()].filter(item => item.document).length);
        return document;
      }) };
      this.entries.set(url, entry);
    }
    if (onProgress) entry.task.onProgress = onProgress;
    return entry.promise;
  }
  async destroy() {
    await Promise.all([...this.entries.values()].map(entry => entry.task.destroy()));
    this.worker?.destroy();
  }
}
