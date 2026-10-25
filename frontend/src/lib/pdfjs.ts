// Client-side PDF rendering for thumbnails and page editors. Files are never
// sent anywhere for previews.
import * as pdfjs from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

export type PDFDoc = pdfjs.PDFDocumentProxy

const docs = new WeakMap<Blob, Promise<PDFDoc>>()

/** Loads (and caches) a PDF. Rejects with name "PasswordException" for encrypted files. */
export function loadPdf(blob: Blob): Promise<PDFDoc> {
  let p = docs.get(blob)
  if (!p) {
    p = blob.arrayBuffer().then((buf) => pdfjs.getDocument({ data: new Uint8Array(buf) }).promise)
    docs.set(blob, p)
  }
  return p
}

/** Renders one page (1-based) to a data URL at roughly the given CSS width. */
export async function renderPage(doc: PDFDoc, pageNumber: number, width: number): Promise<string> {
  const page = await doc.getPage(pageNumber)
  const base = page.getViewport({ scale: 1 })
  const scale = (width * Math.min(window.devicePixelRatio || 1, 2)) / base.width
  const viewport = page.getViewport({ scale })
  const canvas = document.createElement('canvas')
  canvas.width = Math.ceil(viewport.width)
  canvas.height = Math.ceil(viewport.height)
  await page.render({ canvas, viewport }).promise
  const url = canvas.toDataURL('image/jpeg', 0.8)
  page.cleanup()
  return url
}

export function isPasswordError(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { name?: string }).name === 'PasswordException'
}
