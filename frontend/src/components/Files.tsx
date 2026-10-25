import { useEffect, useRef, useState } from 'react'
import { DndContext, PointerSensor, KeyboardSensor, closestCenter, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { SortableContext, arrayMove, rectSortingStrategy, sortableKeyboardCoordinates, useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { AlertCircle, FileText, Lock, Plus, Trash2, Upload } from 'lucide-react'
import clsx from 'clsx'
import type { FileMeta } from '../lib/api'
import { formatBytes } from '../lib/format'
import { isPasswordError, loadPdf, renderPage } from '../lib/pdfjs'

export interface Item {
  key: string
  name: string
  size: number
  blob?: Blob // local bytes for previews
  status: 'uploading' | 'ready' | 'error'
  progress: number
  meta?: FileMeta
  error?: string
}

export const acceptAttr = { pdf: 'application/pdf,.pdf', image: 'image/jpeg,image/png,image/webp,image/tiff,.jpg,.jpeg,.png,.webp,.tif,.tiff' }

/** Opens the native file picker. */
export function usePicker(accept: string, multiple: boolean, onFiles: (f: File[]) => void) {
  const ref = useRef<HTMLInputElement>(null)
  const input = (
    <input
      ref={ref}
      type="file"
      hidden
      accept={accept}
      multiple={multiple}
      onChange={(e) => {
        const files = Array.from(e.target.files ?? [])
        e.target.value = ''
        if (files.length) onFiles(files)
      }}
    />
  )
  return { input, open: () => ref.current?.click() }
}

/** Shows a full-window overlay while files are dragged over the page. */
export function WindowDrop({ onFiles, label }: { onFiles: (f: File[]) => void; label: string }) {
  const [over, setOver] = useState(false)
  const depth = useRef(0)
  useEffect(() => {
    const enter = (e: DragEvent) => {
      if (!e.dataTransfer?.types.includes('Files')) return
      depth.current++
      setOver(true)
    }
    const leave = () => {
      depth.current = Math.max(0, depth.current - 1)
      if (depth.current === 0) setOver(false)
    }
    const over = (e: DragEvent) => e.preventDefault()
    const drop = (e: DragEvent) => {
      e.preventDefault()
      depth.current = 0
      setOver(false)
      const files = Array.from(e.dataTransfer?.files ?? [])
      if (files.length) onFiles(files)
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [onFiles])
  if (!over) return null
  return (
    <div className="pointer-events-none fixed inset-0 z-[60] grid place-items-center bg-brand/10 p-6 backdrop-blur-[2px]">
      <div className="grid w-full max-w-xl place-items-center gap-3 rounded-3xl border-4 border-dashed border-brand bg-surface/90 py-20 text-brand">
        <Upload className="size-12" />
        <div className="text-xl font-bold">{label}</div>
      </div>
    </div>
  )
}

/** First-page thumbnail for a PDF, or the image itself. */
export function Thumb({ blob, mime, className }: { blob?: Blob; mime?: string; className?: string }) {
  const [src, setSrc] = useState<string>()
  const [state, setState] = useState<'loading' | 'ok' | 'locked' | 'error'>('loading')
  useEffect(() => {
    if (!blob) {
      setState('error')
      return
    }
    let url: string | undefined
    let alive = true
    const type = mime ?? blob.type
    if (type.startsWith('image/') && type !== 'image/tiff') {
      url = URL.createObjectURL(blob)
      setSrc(url)
      setState('ok')
    } else if (type === 'application/pdf' || type === '') {
      loadPdf(blob)
        .then((doc) => renderPage(doc, 1, 180))
        .then((u) => alive && (setSrc(u), setState('ok')))
        .catch((e) => alive && setState(isPasswordError(e) ? 'locked' : 'error'))
    } else setState('error')
    return () => {
      alive = false
      if (url) URL.revokeObjectURL(url)
    }
  }, [blob, mime])

  return (
    <div className={clsx('grid place-items-center overflow-hidden bg-surface-2', className)}>
      {state === 'ok' && <img src={src} alt="" className="max-h-full max-w-full object-contain shadow-sm" draggable={false} />}
      {state === 'loading' && <div className="size-6 animate-spin rounded-full border-2 border-line border-t-brand" />}
      {state === 'locked' && <Lock className="size-10 text-muted" />}
      {state === 'error' && <FileText className="size-10 text-muted" />}
    </div>
  )
}

function FileCard({ item, index, sortable, onRemove }: { item: Item; index: number; sortable: boolean; onRemove: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: item.key, disabled: !sortable })
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={clsx(
        'card group relative flex w-full flex-col overflow-hidden p-0 select-none',
        sortable && 'cursor-grab active:cursor-grabbing',
        isDragging && 'z-10 shadow-[var(--shadow-lift)] ring-2 ring-brand',
        item.status === 'error' && 'border-danger',
      )}
      {...attributes}
      {...listeners}
      aria-label={sortable ? `${item.name}, position ${index + 1}. Drag to reorder.` : item.name}
    >
      <Thumb blob={item.blob} mime={item.meta?.mime} className="aspect-[3/4] w-full p-3" />
      {sortable && (
        <span className="absolute left-2 top-2 grid size-6 place-items-center rounded-full bg-fg/80 text-xs font-bold text-surface">{index + 1}</span>
      )}
      <button
        onPointerDown={(e) => e.stopPropagation()}
        onClick={onRemove}
        className="absolute right-2 top-2 grid size-8 place-items-center rounded-full bg-surface text-muted opacity-100 shadow transition hover:text-danger sm:opacity-0 sm:group-hover:opacity-100"
        aria-label={`Remove ${item.name}`}
      >
        <Trash2 className="size-4" />
      </button>
      <div className="border-t border-line px-3 py-2">
        <div className="truncate text-xs font-semibold" title={item.name}>
          {item.name}
        </div>
        <div className="text-xs text-muted">{item.status === 'error' ? <span className="text-danger">{item.error}</span> : formatBytes(item.size)}</div>
      </div>
      {item.status === 'uploading' && (
        <div className="absolute inset-x-0 bottom-0 h-1 bg-line">
          <div className="h-full bg-brand transition-[width]" style={{ width: `${Math.round(item.progress * 100)}%` }} />
        </div>
      )}
      {item.status === 'error' && <AlertCircle className="absolute left-2 top-2 size-6 text-danger" />}
    </div>
  )
}

export function FileGrid({
  items,
  onChange,
  onRemove,
  onAdd,
  sortable,
}: {
  items: Item[]
  onChange: (items: Item[]) => void
  onRemove: (key: string) => void
  onAdd?: () => void
  sortable: boolean
}) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const onDragEnd = (e: DragEndEvent) => {
    if (!e.over || e.active.id === e.over.id) return
    const from = items.findIndex((i) => i.key === e.active.id)
    const to = items.findIndex((i) => i.key === e.over!.id)
    onChange(arrayMove(items, from, to))
  }
  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      <SortableContext items={items.map((i) => i.key)} strategy={rectSortingStrategy}>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {items.map((item, i) => (
            <FileCard key={item.key} item={item} index={i} sortable={sortable} onRemove={() => onRemove(item.key)} />
          ))}
          {onAdd && (
            <button
              onClick={onAdd}
              className="grid aspect-[3/4] place-items-center rounded-2xl border-2 border-dashed border-line text-muted transition hover:border-brand hover:text-brand"
            >
              <span className="grid place-items-center gap-2 text-sm font-semibold">
                <Plus className="size-8" />
                Add more files
              </span>
            </button>
          )}
        </div>
      </SortableContext>
    </DndContext>
  )
}
