const channelNames: Record<string, string> = {
  OFFICE_MANUAL: 'Office 手工录入',
  OFFICE_EXCEL: 'Office Excel 导入',
  OFFICE_LEGACY: 'Office 历史录入',
  ROOMVIP_MANUAL: '主库手工录入',
  ROOMVIP_FACTORY: '主库工厂录入',
  ROOMVIP_EXCEL: '主库 Excel 导入',
}

export function catalogSourceLabel(channel?: string | null, factoryCode?: string | null) {
  const label = channel && channelNames[channel]
  return label ? [factoryCode, label].filter(Boolean).join(' · ') : '历史来源待核验'
}
