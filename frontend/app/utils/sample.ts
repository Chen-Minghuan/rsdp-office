import type { ProjectFile, OfficeObject, FurnitureObject, Product, ProductCombo, AgentMessage, DesignBrief } from '~/types/office'
import type { ManualFloorPlanLayout } from '~/types/manualCanvas'

export const TONES = {
  blue: { stroke: '#82b5e5', fill: '#8fbcea46' },
  green: { stroke: '#8fbda1', fill: '#8ccda04f' },
  yellow: { stroke: '#e6c379', fill: '#f2d58c50' },
  purple: { stroke: '#b39de4', fill: '#ae9ce648' },
  pink: { stroke: '#e8a4b8', fill: '#edb8c44f' },
}

export const ZONE_TYPES: [string, string][] = [
  ['OPEN_OFFICE', '开放办公区'],
  ['MEETING_ROOM', '会议室'],
  ['EXECUTIVE_OFFICE', '经理办公室'],
  ['RECEPTION', '接待区'],
  ['COLLABORATION', '协作区'],
  ['OTHER', '其他区域'],
]

export const PRODUCTS: Product[] = [
  { id: 'SAMPLE-OF-01', name: '四人工位组合', desc: '浅木桌面 / 白色金属框架', variant: '1400mm / 四人组合', price: 5600, kind: 'workstation', uses: ['OPEN_OFFICE', 'OTHER', 'COLLABORATION'], widthCm: 280, depthCm: 140 },
  { id: 'SAMPLE-OF-02', name: '人体工学办公椅', desc: '透气网布 / 可调节扶手', variant: '黑色 / 标准款', price: 980, kind: 'chair', uses: ['OPEN_OFFICE', 'EXECUTIVE_OFFICE', 'MEETING_ROOM', 'OTHER'], widthCm: 60, depthCm: 60 },
  { id: 'SAMPLE-OF-03', name: '会议桌', desc: '暖灰桌面 / 木纹基座', variant: '2400 × 1200 mm', price: 8200, kind: 'meeting', uses: ['MEETING_ROOM', 'OTHER'], widthCm: 240, depthCm: 120 },
  { id: 'SAMPLE-OF-04', name: '会议椅', desc: '浅灰布面 / 金属椅脚', variant: '浅灰色 / 标准', price: 780, kind: 'chair', uses: ['MEETING_ROOM', 'RECEPTION', 'OTHER'], widthCm: 55, depthCm: 55 },
  { id: 'SAMPLE-OF-05', name: '商务接待沙发', desc: '米白布艺 / 双人位', variant: '2000mm / 米白色', price: 4800, kind: 'sofa', uses: ['RECEPTION', 'EXECUTIVE_OFFICE', 'COLLABORATION', 'OTHER'], widthCm: 200, depthCm: 80 },
  { id: 'SAMPLE-OF-06', name: '经理办公桌', desc: '原木纹理 / 储物边柜', variant: '1800 × 800 mm', price: 3900, kind: 'desk', uses: ['EXECUTIVE_OFFICE', 'OTHER'], widthCm: 180, depthCm: 80 },
  { id: 'SAMPLE-OF-07', name: '移动文件柜', desc: '浅灰色 / 三抽屉', variant: '420 × 500 mm', price: 820, kind: 'cabinet', uses: ['OPEN_OFFICE', 'EXECUTIVE_OFFICE', 'OTHER'], widthCm: 42, depthCm: 50 },
  { id: 'SAMPLE-OF-08', name: '茶几', desc: '圆形 / 大理石纹', variant: 'φ800 mm', price: 1200, kind: 'desk', uses: ['RECEPTION', 'COLLABORATION', 'OTHER'], widthCm: 80, depthCm: 80 },
]

export const PRODUCT_COMBOS: ProductCombo[] = [
  {
    id: 'COMBO-WS-01',
    name: '四人工作位组合',
    desc: '1 套四人工位 + 4 把人体工学椅，适配开放办公区',
    style: '现代简约',
    kind: 'workstation',
    uses: ['OPEN_OFFICE', 'COLLABORATION', 'OTHER'],
    items: [
      { productId: 'SAMPLE-OF-01', qty: 1 },
      { productId: 'SAMPLE-OF-02', qty: 4 },
    ],
    price: 9520,
  },
  {
    id: 'COMBO-MT-01',
    name: '会议桌组合',
    desc: '1 张 2.4m 会议桌 + 8 把会议椅，满足中型会议室',
    style: '商务现代',
    kind: 'meeting',
    uses: ['MEETING_ROOM', 'OTHER'],
    items: [
      { productId: 'SAMPLE-OF-03', qty: 1 },
      { productId: 'SAMPLE-OF-04', qty: 8 },
    ],
    price: 14440,
  },
  {
    id: 'COMBO-RC-01',
    name: '接待区沙发组合',
    desc: '1 张双人沙发 + 1 张圆形茶几，营造专业接待氛围',
    style: '轻奢简约',
    kind: 'reception',
    uses: ['RECEPTION', 'OTHER'],
    items: [
      { productId: 'SAMPLE-OF-05', qty: 1 },
      { productId: 'SAMPLE-OF-08', qty: 1 },
    ],
    price: 6000,
  },
  {
    id: 'COMBO-EX-01',
    name: '经理办公室组合',
    desc: '1 张经理办公桌 + 1 把办公椅 + 1 个文件柜',
    style: '原木商务',
    kind: 'executive',
    uses: ['EXECUTIVE_OFFICE', 'OTHER'],
    items: [
      { productId: 'SAMPLE-OF-06', qty: 1 },
      { productId: 'SAMPLE-OF-02', qty: 1 },
      { productId: 'SAMPLE-OF-07', qty: 1 },
    ],
    price: 5520,
  },
]

export function sampleFiles(): ProjectFile[] {
  return [
    { name: '演示办公平面图.pdf', size: 12800000, type: 'PDF', mock: true },
    { name: '现代办公风格参考.png', size: 3200000, type: 'PNG', mock: true },
  ]
}

export function sampleObjects(): OfficeObject[] {
  return [
    { id: 'z-open', kind: 'zone', x: 270, y: 219, w: 445, h: 305, name: '开放办公区', zoneType: 'OPEN_OFFICE', seats: 40, note: '靠窗，注意采光', tone: 'blue' },
    { id: 'z-meeting', kind: 'zone', x: 992, y: 225, w: 247, h: 189, name: '大会议室', zoneType: 'MEETING_ROOM', seats: 12, note: '视频会议与接待客户', tone: 'green' },
    { id: 'z-small', kind: 'zone', x: 994, y: 440, w: 238, h: 120, name: '小会议室', zoneType: 'MEETING_ROOM', seats: 8, note: '日常沟通', tone: 'yellow' },
    { id: 'z-exec', kind: 'zone', x: 269, y: 602, w: 225, h: 177, name: '经理办公室', zoneType: 'EXECUTIVE_OFFICE', seats: 6, note: '相对安静，注重隐私', tone: 'purple' },
    { id: 'z-reception', kind: 'zone', x: 800, y: 601, w: 241, h: 175, name: '接待区', zoneType: 'RECEPTION', seats: 0, note: '展示企业形象', tone: 'pink' },
    { id: 'n-1', kind: 'note', x: 60, y: 76, text: '预计约 60 人\n预算 30 万\n现代简约风格' },
    { id: 'n-2', kind: 'note', x: 1025, y: 40, text: '喜欢浅木色\n空间通透明亮' },
    { id: 'r-1', kind: 'reference', x: 1270, y: 225, caption: '开放办公参考' },
    { id: 'a-1', kind: 'arrow', x: 150, y: 205, x2: 314, y2: 275 },
    // Mock furniture layouts
    { id: 'f-open-1', kind: 'furniture', x: 290, y: 239, w: 140, h: 70, rotation: 0, productId: 'SAMPLE-OF-01', zoneId: 'z-open', label: '四人工位组合 1' } as FurnitureObject,
    { id: 'f-open-2', kind: 'furniture', x: 450, y: 239, w: 140, h: 70, rotation: 0, productId: 'SAMPLE-OF-01', zoneId: 'z-open', label: '四人工位组合 2' } as FurnitureObject,
    { id: 'f-meeting-table', kind: 'furniture', x: 1056, y: 290, w: 120, h: 60, rotation: 0, productId: 'SAMPLE-OF-03', zoneId: 'z-meeting', label: '会议桌' } as FurnitureObject,
    { id: 'f-meeting-chair-1', kind: 'furniture', x: 1102, y: 245, w: 28, h: 28, rotation: 0, productId: 'SAMPLE-OF-04', zoneId: 'z-meeting', label: '会议椅 1' } as FurnitureObject,
    { id: 'f-meeting-chair-2', kind: 'furniture', x: 1102, y: 367, w: 28, h: 28, rotation: 0, productId: 'SAMPLE-OF-04', zoneId: 'z-meeting', label: '会议椅 2' } as FurnitureObject,
    { id: 'f-meeting-chair-3', kind: 'furniture', x: 1012, y: 306, w: 28, h: 28, rotation: 0, productId: 'SAMPLE-OF-04', zoneId: 'z-meeting', label: '会议椅 3' } as FurnitureObject,
    { id: 'f-meeting-chair-4', kind: 'furniture', x: 1192, y: 306, w: 28, h: 28, rotation: 0, productId: 'SAMPLE-OF-04', zoneId: 'z-meeting', label: '会议椅 4' } as FurnitureObject,
    { id: 'f-exec-desk', kind: 'furniture', x: 337, y: 671, w: 90, h: 40, rotation: 0, productId: 'SAMPLE-OF-06', zoneId: 'z-exec', label: '经理办公桌' } as FurnitureObject,
    { id: 'f-exec-chair', kind: 'furniture', x: 367, y: 721, w: 30, h: 30, rotation: 0, productId: 'SAMPLE-OF-02', zoneId: 'z-exec', label: '办公椅' } as FurnitureObject,
    { id: 'f-reception-sofa', kind: 'furniture', x: 871, y: 669, w: 100, h: 40, rotation: 0, productId: 'SAMPLE-OF-05', zoneId: 'z-reception', label: '接待沙发' } as FurnitureObject,
  ]
}

export function createWelcomeMessages(submitted: DesignBrief): AgentMessage[] {
  return [
    {
      role: 'bot',
      content: `已读取办公空间画布（${submitted.canvasRevisionId}）。\n\n画布已标注 ${submitted.zones.length} 个功能区。\n\n请先选择左侧区域，我可以根据区域用途给您展示办公家具的演示候选。实际尺寸与工位容量仍需校准确认。`,
    },
  ]
}

export function sampleManualLayout(): ManualFloorPlanLayout {
  const openOffice: import('~/types/manualCanvas').ManualLayoutRoom = {
    clientId: 'room-open-01',
    label: '开放办公区',
    roomType: 'OPEN_OFFICE',
    parts: [
      { id: 'part-open-a', x: 0, y: 0, w: 6000, h: 4000 },
      { id: 'part-open-b', x: 6000, y: 0, w: 2000, h: 6000 },
    ],
    placementZone: { id: null, x: 500, y: 500, w: 5000, h: 3000 },
    placementZones: [
      { id: null, x: 500, y: 500, w: 5000, h: 3000 },
      { id: null, x: 6200, y: 500, w: 1400, h: 5000 },
    ],
  }
  const meetingRoom: import('~/types/manualCanvas').ManualLayoutRoom = {
    clientId: 'room-meeting-01',
    label: '会议室',
    roomType: 'MEETING_ROOM',
    parts: [
      { id: 'part-meeting-a', x: 8500, y: 0, w: 4000, h: 3000 },
    ],
    placementZone: { id: null, x: 8700, y: 200, w: 3600, h: 2600 },
    placementZones: [{ id: null, x: 8700, y: 200, w: 3600, h: 2600 }],
  }
  return {
    version: 3,
    canvas: { widthMm: 16000, heightMm: 9000, gridMm: 100, mode: 'unbounded' },
    rooms: [openOffice, meetingRoom],
    furniture: [
      {
        id: 'f-desk-01',
        templateId: 'desk-straight',
        roomClientId: 'room-open-01',
        xMm: 1200,
        yMm: 1200,
        widthMm: 1400,
        depthMm: 700,
        rotationDeg: 0,
      },
      {
        id: 'f-desk-02',
        templateId: 'desk-straight',
        roomClientId: 'room-open-01',
        xMm: 3000,
        yMm: 1200,
        widthMm: 1400,
        depthMm: 700,
        rotationDeg: 0,
      },
      {
        id: 'f-chair-01',
        templateId: 'chair-office',
        roomClientId: 'room-open-01',
        xMm: 1200,
        yMm: 2000,
        widthMm: 650,
        depthMm: 650,
        rotationDeg: 0,
      },
      {
        id: 'f-table-01',
        templateId: 'table-conference-rect',
        roomClientId: 'room-meeting-01',
        xMm: 10500,
        yMm: 1500,
        widthMm: 2800,
        depthMm: 1200,
        rotationDeg: 0,
      },
    ],
  }
}
