import { ref } from 'vue'

/** 同一次空格按压只用于平移，或在松开时触发一次选中对象的快捷操作。 */
export function useCanvasSpacePan() {
  const pointerInside = ref(false)
  const spacePanHeld = ref(false)
  let usedForPan = false

  function clearSpacePan() {
    spacePanHeld.value = false
    usedForPan = false
  }

  function onCanvasEnter() {
    pointerInside.value = true
  }

  function onCanvasLeave() {
    pointerInside.value = false
    clearSpacePan()
  }

  function holdSpace(event: KeyboardEvent, enabled: boolean, allowFocusedTap = false) {
    if ((!pointerInside.value && !allowFocusedTap) || !enabled || (event.repeat && !spacePanHeld.value)
      || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
    event.preventDefault()
    spacePanHeld.value = true
  }

  function takeSpacePan(event: PointerEvent) {
    if (!pointerInside.value || !spacePanHeld.value || event.button !== 0) return false
    usedForPan = true
    return true
  }

  function releaseSpace() {
    const tapped = spacePanHeld.value && !usedForPan
    clearSpacePan()
    return tapped
  }

  return { spacePanHeld, onCanvasEnter, onCanvasLeave, holdSpace, takeSpacePan, releaseSpace, clearSpacePan }
}
