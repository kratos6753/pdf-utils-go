import { useRef } from 'react'

export const acceptAttr = { pdf: 'application/pdf,.pdf', image: 'image/jpeg,image/png,image/webp,image/tiff,.jpg,.jpeg,.png,.webp,.tif,.tiff' }

/** A hidden file input plus a function that opens the native file picker. */
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
