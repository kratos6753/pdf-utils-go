import { useEffect, useRef, useState } from 'react'
import { DndContext, PointerSensor, KeyboardSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { SortableContext, arrayMove, rectSortingStrategy, sortableKeyboardCoordinates, useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Lock, RotateCcw, Trash2, X } from 'lucide-react'
import clsx from 'clsx'
import { isPasswordError, loadPdf, renderPage, type PDFDoc } from '../lib/pdfjs'

/** Renders a page thumbnail once it scrolls into view. */
function PageThumb({ doc, page }: { doc: PDFDoc; page: number }) {
  const ref = useRef<HTMLDivElement>(null)
  const [src, setSrc] = useState<string>()
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let alive = true
    const io = new IntersectionObserver(
      ([e]) => {
        if (!e.isIntersecting) return
        io.disconnect()
        renderPage(doc, page, 150).then((u) => alive && setSrc(u)).catch(() => {})
      },
      { rootMargin: '300px' },
    )
    io.observe(el)
    return () => {
      alive = false
      io.disconnect()
    }
  }, [doc, page])
  return (
    <div ref={ref} className="grid aspect-[3/4] w-full place-items-center bg-surface-2 p-2">
      {src ? <img src={src} alt={`Page ${page}`} className="max-h-full max-w-full shadow-sm" draggable={false} /> : <div className="size-5 animate-spin rounded-full border-2 border-line border-t-brand" />}
    </div>
  )
}

function SortablePage({ id, doc, page, position, onDelete }: { id: string; doc: PDFDoc; page: number; position: number; onDelete: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id })
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={clsx('card group relative cursor-grab overflow-hidden select-none active:cursor-grabbing', isDragging && 'z-10 ring-2 ring-brand')}
      {...attributes}
      {...listeners}
      aria-label={`Page ${page}, position ${position}. Drag to reorder.`}
    >
      <PageThumb doc={doc} page={page} />
      <button
        onPointerDown={(e) => e.stopPropagation()}
        onClick={onDelete}
        className="absolute right-1.5 top-1.5 grid size-7 place-items-center rounded-full bg-surface text-muted shadow transition hover:text-danger sm:opacity-0 sm:group-hover:opacity-100"
        aria-label={`Delete page ${page}`}
      >
        <X className="size-4" />
      </button>
      <div className="border-t border-line py-1 text-center text-xs font-semibold text-muted">
        {position}
        {position !== page && <span className="font-normal"> (was {page})</span>}
      </div>
    </div>
  )
}

export function PageEditor({
  blob,
  mode,
  onOrder,
  onRemoved,
  onPageCount,
}: {
  blob: Blob
  mode: 'organize' | 'remove'
  onOrder?: (order: number[]) => void
  onRemoved?: (pages: number[]) => void
  onPageCount?: (n: number) => void
}) {
  const [doc, setDoc] = useState<PDFDoc>()
  const [error, setError] = useState<string>()
  const [pages, setPages] = useState<{ id: string; page: number }[]>([])
  const [removed, setRemoved] = useState<Set<number>>(new Set())

  useEffect(() => {
    let alive = true
    setDoc(undefined)
    setError(undefined)
    loadPdf(blob)
      .then((d) => {
        if (!alive) return
        setDoc(d)
        setPages(Array.from({ length: d.numPages }, (_, i) => ({ id: `p${i + 1}`, page: i + 1 })))
        setRemoved(new Set())
        onPageCount?.(d.numPages)
      })
      .catch((e) => alive && setError(isPasswordError(e) ? 'locked' : 'This PDF could not be previewed.'))
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blob])

  useEffect(() => {
    onOrder?.(pages.map((p) => p.page))
  }, [pages, onOrder])
  useEffect(() => {
    onRemoved?.([...removed].sort((a, b) => a - b))
  }, [removed, onRemoved])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  if (error === 'locked')
    return (
      <div className="card grid place-items-center gap-2 p-10 text-center text-muted">
        <Lock className="size-10" />
        This PDF is password protected. Unlock it first to edit its pages.
      </div>
    )
  if (error) return <div className="card p-10 text-center text-muted">{error}</div>
  if (!doc)
    return (
      <div className="grid place-items-center p-16">
        <div className="size-8 animate-spin rounded-full border-2 border-line border-t-brand" />
      </div>
    )

  const total = doc.numPages
  const header = (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted">
      <span>
        {mode === 'organize'
          ? `${pages.length} of ${total} pages · drag to reorder`
          : removed.size
            ? `${removed.size} page${removed.size > 1 ? 's' : ''} selected for removal`
            : 'Click pages to mark them for removal'}
      </span>
      <button
        className="btn-ghost px-2 py-1"
        onClick={() => {
          setPages(Array.from({ length: total }, (_, i) => ({ id: `p${i + 1}`, page: i + 1 })))
          setRemoved(new Set())
        }}
      >
        <RotateCcw className="size-4" /> Reset
      </button>
    </div>
  )
  const grid = 'grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6'

  if (mode === 'remove')
    return (
      <div>
        {header}
        <div className={grid}>
          {Array.from({ length: total }, (_, i) => i + 1).map((p) => {
            const on = removed.has(p)
            return (
              <button
                key={p}
                onClick={() =>
                  setRemoved((s) => {
                    const n = new Set(s)
                    if (on) n.delete(p)
                    else n.add(p)
                    return n
                  })
                }
                className={clsx('card relative overflow-hidden text-left transition', on && 'border-danger ring-2 ring-danger')}
                aria-pressed={on}
              >
                <div className={clsx(on && 'opacity-40')}>
                  <PageThumb doc={doc} page={p} />
                </div>
                {on && (
                  <span className="absolute inset-0 grid place-items-center">
                    <span className="grid size-10 place-items-center rounded-full bg-danger text-white">
                      <Trash2 className="size-5" />
                    </span>
                  </span>
                )}
                <div className="border-t border-line py-1 text-center text-xs font-semibold text-muted">{p}</div>
              </button>
            )
          })}
        </div>
      </div>
    )

  const onDragEnd = (e: DragEndEvent) => {
    if (!e.over || e.active.id === e.over.id) return
    setPages((ps) => arrayMove(ps, ps.findIndex((p) => p.id === e.active.id), ps.findIndex((p) => p.id === e.over!.id)))
  }
  return (
    <div>
      {header}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        <SortableContext items={pages.map((p) => p.id)} strategy={rectSortingStrategy}>
          <div className={grid}>
            {pages.map((p, i) => (
              <SortablePage key={p.id} id={p.id} doc={doc} page={p.page} position={i + 1} onDelete={() => setPages((ps) => ps.filter((x) => x.id !== p.id))} />
            ))}
          </div>
        </SortableContext>
      </DndContext>
    </div>
  )
}
