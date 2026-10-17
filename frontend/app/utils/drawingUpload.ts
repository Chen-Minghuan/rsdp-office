import type { ProjectFile } from '~/types/office'

export function drawingFileDescriptor(file: File): ProjectFile {
  const extension = (file.name.split('.').pop() || '').toLowerCase()
  if (!['pdf', 'png', 'jpg', 'jpeg', 'dwg', 'dxf'].includes(extension)) {
    throw new Error('请选择 PDF、PNG、JPG、DWG 或 DXF 图纸文件。')
  }
  return { drawingId: crypto.randomUUID(), name: file.name, size: file.size, type: extension.toUpperCase(), mock: false }
}
