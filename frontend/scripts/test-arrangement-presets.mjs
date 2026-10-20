import assert from 'node:assert/strict'

const {
  OFFICE_ARRANGEMENT_PRESETS,
  OFFICE_PRODUCT_STYLES,
  officeArrangementPreset,
  presetsForProductStyle,
  productStyleForTemplate,
} = await import('../app/utils/officeArrangementPresets.ts')

assert.equal(OFFICE_PRODUCT_STYLES.length, 4)
assert.ok(OFFICE_ARRANGEMENT_PRESETS.length >= 18)
assert.deepEqual(presetsForProductStyle('straight-desk').map(preset => preset.id), ['straight-desk-row', 'straight-desk-face-to-face'])
assert.deepEqual(presetsForProductStyle('l-desk').map(preset => preset.id), ['l-desk-row', 'l-desk-face-to-face'])
assert.deepEqual(presetsForProductStyle('straight-screen').map(preset => preset.id), ['straight-screen-row', 'straight-screen-face-to-face'])
assert.deepEqual(presetsForProductStyle('l-screen').map(preset => preset.id), ['screen-f-row', 'screen-t-face-to-face', 'screen-c-face-to-face', 'screen-cross-face-to-face'])
assert.ok(!presetsForProductStyle('l-screen').some(preset => ['l-screen-row', 'l-screen-face-to-face'].includes(preset.id)), 'L 型屏风只允许图示的 F/T/C/十字型')
assert.ok(!presetsForProductStyle('straight-desk').some(preset => preset.id.startsWith('generic-')), '新界面不得展示通用模板')
assert.equal(productStyleForTemplate({ kind: 'desk', name: '职员直线桌' }), 'straight-desk')
assert.equal(productStyleForTemplate({ kind: 'l-desk', name: '职员 L 桌' }), 'l-desk')
assert.equal(productStyleForTemplate({ kind: 'desk', name: '一字屏风卡位' }), 'straight-screen')
assert.equal(productStyleForTemplate({ kind: 'l-desk', name: 'L 型屏风卡位' }), 'l-screen')
assert.equal(officeArrangementPreset('straight-desk-face-to-face').defaults.faceGapMm, 1600)
assert.equal(officeArrangementPreset('straight-desk-row').defaults.faceGapMm, 1000)
assert.equal(officeArrangementPreset('l-desk-row').defaults.faceGapMm, 600)
assert.equal(officeArrangementPreset('screen-t-face-to-face').defaults.faceGapMm, 900)
assert.equal(officeArrangementPreset('straight-screen-face-to-face').defaults.faceGapMm, 1600)
assert.ok(presetsForProductStyle('l-screen').some(preset => preset.id === 'screen-cross-face-to-face'))
assert.deepEqual(presetsForProductStyle('general').map(preset => preset.id), ['general-center', 'general-auto'])
assert.equal(productStyleForTemplate({id:'UMOD-CUSTOM',glyphTemplateId:'desk-screen-l',category:'职员桌',kind:'rounded-l-desk',name:'自定义模块'}),'l-screen')
assert.equal(productStyleForTemplate({id:'UMOD-DESK',glyphTemplateId:'desk-straight',category:'职员桌',kind:'desk',name:'屏风命名的普通桌'}),'straight-desk')
assert.equal(productStyleForTemplate({id:'cabinet-mobile',category:'收纳',kind:'cabinet',name:'活动柜'}),'general')
for (const preset of OFFICE_ARRANGEMENT_PRESETS) {
  assert.ok(preset.styleIds.length > 0, `${preset.id} must have a compatible product style`)
  assert.ok(['row', 'paired', 'aisle', 'perimeter'].includes(preset.topology), `${preset.id} topology`)
}
console.log('产品样式与排布模板契约通过：四类产品样式、素材默认值、兼容关系和模板拓扑均有效。')
