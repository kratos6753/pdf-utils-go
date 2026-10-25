import { execFileSync } from 'node:child_process'
import { mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'

const dir = path.join(import.meta.dirname, '.fixtures')

/** Generates an n-page PDF with Ghostscript (cached). */
export function pdf(name: string, pages: number): string {
  mkdirSync(dir, { recursive: true })
  const out = path.join(dir, `${name}.pdf`)
  if (!existsSync(out)) {
    execFileSync('gs', ['-q', '-dNOPAUSE', '-dBATCH', '-sDEVICE=pdfwrite', `-sOutputFile=${out}`, '-c',
      `/Helvetica findfont 48 scalefont setfont 1 1 ${pages} { 72 600 moveto (${name} page ) show 3 string cvs show showpage } for`])
  }
  return out
}
