// The tool registry: everything the UI needs to render a tool page. Adding a
// backend tool means adding one entry here.
import {
  Combine,
  FileImage,
  FileMinus,
  Hash,
  ImagePlus,
  LayoutGrid,
  Lock,
  LockOpen,
  Minimize2,
  RotateCw,
  Scissors,
  Stamp,
  type LucideIcon,
} from 'lucide-react'

export type Category = 'organize' | 'optimize' | 'convert' | 'edit' | 'security'

export const categories: { id: Category; label: string }[] = [
  { id: 'organize', label: 'Organize' },
  { id: 'optimize', label: 'Optimize' },
  { id: 'convert', label: 'Convert' },
  { id: 'edit', label: 'Edit' },
  { id: 'security', label: 'Security' },
]

export type Values = Record<string, string | number | boolean>

export type Field = {
  key: string
  label: string
  hint?: string
  showIf?: (v: Values) => boolean
} & (
  | { type: 'choice'; options: { value: string; label: string; hint?: string }[]; default: string; layout?: 'cards' | 'pills' }
  | { type: 'text'; placeholder?: string; default?: string; required?: boolean; maxLength?: number }
  | { type: 'pages'; placeholder?: string; required?: boolean }
  | { type: 'password'; confirm?: boolean; required?: boolean }
  | { type: 'range'; min: number; max: number; step: number; default: number; unit?: string }
  | { type: 'color'; default: string }
  | { type: 'toggle'; default: boolean }
)

export interface Tool {
  slug: string // URL path
  api: string // backend tool name
  title: string
  verb: string // action button label
  description: string
  category: Category
  icon: LucideIcon
  accepts: 'pdf' | 'image'
  multiple: boolean
  minFiles: number
  fields: Field[]
  /** Page-level editor shown instead of (or above) fields. */
  pageEditor?: 'organize' | 'remove'
  /** Turns form values into API options; defaults to passing values through. */
  toOptions?: (v: Values, ctx: { pageOrder?: number[]; removed?: number[] }) => Record<string, unknown>
  /** Extra check before submitting; returns an error message. */
  validate?: (v: Values, ctx: { pageOrder?: number[]; removed?: number[]; pageCount?: number }) => string | null
}

const pageHint = 'e.g. 1-3, 5, 8-10'

export const tools: Tool[] = [
  {
    slug: 'merge-pdf',
    api: 'merge',
    title: 'Merge PDF',
    verb: 'Merge PDF',
    description: 'Combine PDFs in the order you want into a single file.',
    category: 'organize',
    icon: Combine,
    accepts: 'pdf',
    multiple: true,
    minFiles: 2,
    fields: [],
  },
  {
    slug: 'split-pdf',
    api: 'split',
    title: 'Split PDF',
    verb: 'Split PDF',
    description: 'Separate pages into independent files, by range or every N pages.',
    category: 'organize',
    icon: Scissors,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      {
        key: 'mode',
        label: 'Split mode',
        type: 'choice',
        layout: 'cards',
        default: 'ranges',
        options: [
          { value: 'ranges', label: 'By range', hint: 'One file per range' },
          { value: 'every', label: 'Fixed size', hint: 'Every N pages' },
          { value: 'extract', label: 'Extract pages', hint: 'Pick specific pages' },
        ],
      },
      { key: 'ranges', label: 'Ranges', type: 'text', placeholder: '1-3, 4-6, 7-10', hint: 'Each range becomes its own PDF.', showIf: (v) => v.mode === 'ranges', required: true },
      { key: 'every', label: 'Pages per file', type: 'range', min: 1, max: 50, step: 1, default: 1, showIf: (v) => v.mode === 'every' },
      { key: 'pages', label: 'Pages to extract', type: 'pages', placeholder: pageHint, showIf: (v) => v.mode === 'extract', required: true },
      { key: 'merge', label: 'Merge extracted pages into one PDF', type: 'toggle', default: true, showIf: (v) => v.mode === 'extract' },
    ],
    toOptions: (v) => {
      if (v.mode === 'ranges') return { mode: 'ranges', ranges: String(v.ranges).split(',').map((s) => s.trim()).filter(Boolean) }
      if (v.mode === 'every') return { mode: 'every', every: Number(v.every) }
      return { mode: 'extract', pages: v.pages, merge: v.merge }
    },
  },
  {
    slug: 'compress-pdf',
    api: 'compress',
    title: 'Compress PDF',
    verb: 'Compress PDF',
    description: 'Reduce file size while keeping the best possible quality.',
    category: 'optimize',
    icon: Minimize2,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      {
        key: 'level',
        label: 'Compression level',
        type: 'choice',
        layout: 'cards',
        default: 'recommended',
        options: [
          { value: 'extreme', label: 'Extreme', hint: 'Smallest file, lower quality' },
          { value: 'recommended', label: 'Recommended', hint: 'Good quality, good compression' },
          { value: 'low', label: 'Less compression', hint: 'High quality, larger file' },
        ],
      },
    ],
  },
  {
    slug: 'jpg-to-pdf',
    api: 'image_to_pdf',
    title: 'JPG to PDF',
    verb: 'Convert to PDF',
    description: 'Turn JPG, PNG, WEBP and TIFF images into a PDF in seconds.',
    category: 'convert',
    icon: ImagePlus,
    accepts: 'image',
    multiple: true,
    minFiles: 1,
    fields: [
      {
        key: 'page_size',
        label: 'Page size',
        type: 'choice',
        layout: 'pills',
        default: 'fit',
        options: [
          { value: 'fit', label: 'Fit to image' },
          { value: 'A4', label: 'A4' },
          { value: 'Letter', label: 'US Letter' },
        ],
      },
      {
        key: 'orientation',
        label: 'Orientation',
        type: 'choice',
        layout: 'pills',
        default: 'portrait',
        options: [
          { value: 'portrait', label: 'Portrait' },
          { value: 'landscape', label: 'Landscape' },
        ],
        showIf: (v) => v.page_size !== 'fit',
      },
      {
        key: 'margin',
        label: 'Margin',
        type: 'choice',
        layout: 'pills',
        default: 'none',
        options: [
          { value: 'none', label: 'No margin' },
          { value: 'small', label: 'Small' },
          { value: 'big', label: 'Big' },
        ],
        showIf: (v) => v.page_size !== 'fit',
      },
    ],
  },
  {
    slug: 'pdf-to-jpg',
    api: 'pdf_to_image',
    title: 'PDF to JPG',
    verb: 'Convert to images',
    description: 'Convert every PDF page into a high-quality JPG or PNG image.',
    category: 'convert',
    icon: FileImage,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      {
        key: 'format',
        label: 'Image format',
        type: 'choice',
        layout: 'pills',
        default: 'jpg',
        options: [
          { value: 'jpg', label: 'JPG' },
          { value: 'png', label: 'PNG' },
        ],
      },
      {
        key: 'dpi',
        label: 'Quality',
        type: 'choice',
        layout: 'pills',
        default: '150',
        options: [
          { value: '72', label: 'Web (72 dpi)' },
          { value: '150', label: 'Standard (150 dpi)' },
          { value: '300', label: 'High (300 dpi)' },
        ],
      },
      { key: 'pages', label: 'Pages (optional)', type: 'pages', placeholder: 'All pages' },
    ],
    toOptions: (v) => ({ format: v.format, dpi: Number(v.dpi), pages: v.pages || undefined }),
  },
  {
    slug: 'rotate-pdf',
    api: 'rotate',
    title: 'Rotate PDF',
    verb: 'Rotate PDF',
    description: 'Rotate all pages, or just the ones you choose.',
    category: 'organize',
    icon: RotateCw,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      {
        key: 'angle',
        label: 'Rotation',
        type: 'choice',
        layout: 'pills',
        default: '90',
        options: [
          { value: '90', label: '90° right' },
          { value: '180', label: '180°' },
          { value: '270', label: '90° left' },
        ],
      },
      { key: 'pages', label: 'Pages (optional)', type: 'pages', placeholder: 'All pages' },
    ],
    toOptions: (v) => ({ angle: Number(v.angle), pages: v.pages || undefined }),
  },
  {
    slug: 'organize-pdf',
    api: 'organize',
    title: 'Organize PDF',
    verb: 'Save changes',
    description: 'Drag to reorder pages or delete the ones you do not need.',
    category: 'organize',
    icon: LayoutGrid,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [],
    pageEditor: 'organize',
    toOptions: (_v, ctx) => ({ order: ctx.pageOrder }),
    validate: (_v, ctx) => (ctx.pageOrder?.length ? null : 'Keep at least one page'),
  },
  {
    slug: 'remove-pages',
    api: 'remove_pages',
    title: 'Remove pages',
    verb: 'Remove pages',
    description: 'Click the pages you want to delete from your PDF.',
    category: 'organize',
    icon: FileMinus,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [],
    pageEditor: 'remove',
    toOptions: (_v, ctx) => ({ pages: (ctx.removed ?? []).join(',') }),
    validate: (_v, ctx) => {
      if (!ctx.removed?.length) return 'Select at least one page to remove'
      if (ctx.pageCount && ctx.removed.length >= ctx.pageCount) return 'You cannot remove every page'
      return null
    },
  },
  {
    slug: 'add-watermark',
    api: 'watermark',
    title: 'Add watermark',
    verb: 'Add watermark',
    description: 'Stamp text over your PDF pages, with the opacity and angle you choose.',
    category: 'edit',
    icon: Stamp,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      { key: 'text', label: 'Watermark text', type: 'text', placeholder: 'CONFIDENTIAL', required: true, maxLength: 200 },
      { key: 'font_size', label: 'Font size', type: 'range', min: 12, max: 120, step: 2, default: 48, unit: 'pt' },
      { key: 'opacity', label: 'Opacity', type: 'range', min: 0.1, max: 1, step: 0.05, default: 0.3 },
      { key: 'rotation', label: 'Angle', type: 'range', min: -90, max: 90, step: 5, default: 45, unit: '°' },
      { key: 'color', label: 'Color', type: 'color', default: '#808080' },
      {
        key: 'position',
        label: 'Position',
        type: 'choice',
        layout: 'pills',
        default: 'c',
        options: [
          { value: 'tl', label: 'Top left' },
          { value: 'tc', label: 'Top' },
          { value: 'c', label: 'Center' },
          { value: 'bc', label: 'Bottom' },
          { value: 'br', label: 'Bottom right' },
        ],
      },
    ],
  },
  {
    slug: 'add-page-numbers',
    api: 'page_numbers',
    title: 'Add page numbers',
    verb: 'Add page numbers',
    description: 'Number your pages with the position and format you need.',
    category: 'edit',
    icon: Hash,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [
      {
        key: 'position',
        label: 'Position',
        type: 'choice',
        layout: 'pills',
        default: 'bc',
        options: [
          { value: 'bl', label: 'Bottom left' },
          { value: 'bc', label: 'Bottom center' },
          { value: 'br', label: 'Bottom right' },
          { value: 'tl', label: 'Top left' },
          { value: 'tc', label: 'Top center' },
          { value: 'tr', label: 'Top right' },
        ],
      },
      {
        key: 'format',
        label: 'Format',
        type: 'choice',
        layout: 'pills',
        default: 'n',
        options: [
          { value: 'n', label: '1' },
          { value: 'n_of_total', label: '1 of 10' },
          { value: 'page_n', label: 'Page 1' },
          { value: 'page_n_of_total', label: 'Page 1 of 10' },
        ],
      },
      { key: 'font_size', label: 'Font size', type: 'range', min: 8, max: 24, step: 1, default: 11, unit: 'pt' },
      { key: 'pages', label: 'Pages (optional)', type: 'pages', placeholder: 'All pages' },
    ],
    toOptions: (v) => ({ ...v, pages: v.pages || undefined }),
  },
  {
    slug: 'protect-pdf',
    api: 'protect',
    title: 'Protect PDF',
    verb: 'Protect PDF',
    description: 'Encrypt your PDF with a password (AES-256) to keep it private.',
    category: 'security',
    icon: Lock,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [{ key: 'password', label: 'Password', type: 'password', confirm: true, required: true }],
  },
  {
    slug: 'unlock-pdf',
    api: 'unlock',
    title: 'Unlock PDF',
    verb: 'Unlock PDF',
    description: 'Remove the password and restrictions from a PDF you can open.',
    category: 'security',
    icon: LockOpen,
    accepts: 'pdf',
    multiple: false,
    minFiles: 1,
    fields: [{ key: 'password', label: 'Current password', type: 'password', hint: 'Leave empty if the PDF opens without a password but is restricted.' }],
  },
]

export const toolBySlug = (slug: string) => tools.find((t) => t.slug === slug)

export const categoryColor: Record<Category, string> = {
  organize: 'var(--color-cat-organize)',
  optimize: 'var(--color-cat-optimize)',
  convert: 'var(--color-cat-convert)',
  edit: 'var(--color-cat-edit)',
  security: 'var(--color-cat-security)',
}

export function defaultValues(tool: Tool): Values {
  const v: Values = {}
  for (const f of tool.fields) {
    v[f.key] = 'default' in f && f.default !== undefined ? f.default : ''
  }
  return v
}

const pagesRe = /^\s*\d+(\s*-\s*\d+)?(\s*,\s*\d+(\s*-\s*\d+)?)*\s*$/

/** Client-side field validation; the server re-validates everything. */
export function validateFields(tool: Tool, v: Values, confirm: Record<string, string>): string | null {
  for (const f of tool.fields) {
    if (f.showIf && !f.showIf(v)) continue
    const val = v[f.key]
    if ((f.type === 'text' || f.type === 'pages' || f.type === 'password') && f.required && !String(val ?? '').trim())
      return `${f.label} is required`
    if (f.type === 'pages' && val && !pagesRe.test(String(val))) return `${f.label}: use page numbers like ${pageHint}`
    if (f.key === 'ranges' && val && !String(val).split(',').every((r) => pagesRe.test(r))) return 'Ranges: use e.g. 1-3, 4-6'
    if (f.type === 'password' && f.confirm && val !== confirm[f.key]) return 'Passwords do not match'
  }
  return null
}

/** Tools an output of this mime type can be chained into. */
export function nextTools(current: Tool, mime: string): Tool[] {
  const kind = mime === 'application/pdf' ? 'pdf' : mime.startsWith('image/') ? 'image' : null
  if (!kind) return []
  const preferred = ['compress-pdf', 'merge-pdf', 'split-pdf', 'protect-pdf', 'add-page-numbers', 'organize-pdf', 'add-watermark', 'pdf-to-jpg']
  return preferred
    .map((s) => toolBySlug(s)!)
    .filter((t) => t.slug !== current.slug && t.accepts === kind)
    .slice(0, 6)
}
