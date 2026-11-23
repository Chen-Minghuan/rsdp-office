# Product Image Preview Responsive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the recommendation image preview and its data sidebar fully inside the viewport while preserving the image aspect ratio.

**Architecture:** Extract the pure preview geometry calculation from the Vue component into a typed utility, so viewport, image, sidebar, padding, gap, and anchor bounds are constrained together. The component supplies measured sidebar content height and renders the resulting rectangle; an executable Node regression script covers wide images, narrow viewports, tall sidebars, and legacy no-sidebar behavior.

**Tech Stack:** Nuxt 4, Vue 3, TypeScript, Node.js executable test scripts

**Spec:** `docs/superpowers/specs/2026-11-20-product-image-preview-responsive-design.md`

## Global Constraints

- Data sidebar text must not be scaled.
- Images use their natural aspect ratio and `contain` behavior.
- The floating preview remains within a 12px viewport safety margin.
- Tall sidebar content scrolls inside the sidebar; the floating frame does not exceed available viewport height.
- Do not change detail-page magnifier or lightbox behavior.

## Review Focus

- Viewports narrower than the preferred sidebar plus image width must still produce non-negative, in-viewport geometry.
- Ultra-wide images such as 1600×300 must not hide the sidebar.
- Ultra-tall images must be height-constrained without distortion.
- Sidebar content taller than the viewport must scroll inside a bounded frame.
- Previews without a sidebar must preserve the existing maximum 560×620 image behavior.

---

### Task 1: Pure preview geometry and regression coverage

**Files:**
- Create: `frontend/app/utils/productImagePreview.ts`
- Create: `frontend/scripts/test-product-image-preview.mjs`
- Modify: `frontend/package.json`

**Interfaces:**
- Produces: `calculateProductImagePreview(input: ProductImagePreviewInput): ProductImagePreviewRect`
- The result contains numeric `left`, `top`, `width`, `height`, `imageWidth`, `imageHeight`, and `sideWidth` values.

- [ ] **Step 1: Write the failing geometry regression script**

Assert that a 1600×300 image with a sidebar remains inside a 1200×800 viewport, a sidebar preview remains inside a 768px viewport, a tall sidebar is height-bounded, an ultra-tall image preserves its ratio, and a no-sidebar preview retains its maximum bounds.

- [ ] **Step 2: Run the regression script and verify RED**

Run: `pnpm test:product-image-preview`

Expected: FAIL because `app/utils/productImagePreview.ts` does not exist.

- [ ] **Step 3: Implement the typed pure calculation**

Use one scale factor derived from both remaining viewport width and available viewport height. Include outer safety margins, frame padding, and column gap in the total frame calculation; reduce the sidebar width only when the viewport cannot fit its preferred width plus a useful image region.

- [ ] **Step 4: Run the regression script and verify GREEN**

Run: `pnpm test:product-image-preview`

Expected: all preview geometry assertions pass.

### Task 2: Integrate measured responsive geometry into the Vue preview

**Files:**
- Modify: `frontend/app/components/ProductImagePreview.vue`
- Modify: `frontend/app/utils/productImagePreview.ts`
- Test: `frontend/scripts/test-product-image-preview.mjs`

**Interfaces:**
- Consumes: `calculateProductImagePreview`
- Produces: inline frame dimensions plus CSS custom properties for image/sidebar column widths.

- [ ] **Step 1: Add failing assertions for component-facing style conversion**

Assert the helper exposes geometry sufficient for the component to set frame size and sidebar width without reintroducing independent fixed-width arithmetic.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `pnpm test:product-image-preview`

Expected: FAIL on the new component-facing contract.

- [ ] **Step 3: Replace component-local arithmetic with the pure helper**

Keep `ResizeObserver` measurement, bind computed sidebar width, use `overflow-y-auto` for tall content, and preserve all large-image magnifier/lightbox paths.

- [ ] **Step 4: Run focused test and build**

Run: `pnpm test:product-image-preview`

Run: `pnpm build`

Expected: both pass without errors.

### Task 3: Record verification and inspect the final diff

**Files:**
- Modify: `docs/tasks/CAT-005-product-image-gallery.md`
- Modify: `docs/status.md`

**Interfaces:**
- Consumes: verified behavior from Tasks 1–2.
- Produces: an honest record of automated coverage and any manual verification not performed.

- [ ] **Step 1: Record behavior, commands, and limitations**

Document the two repaired cases and distinguish geometry-script/build evidence from real-browser verification.

- [ ] **Step 2: Run final verification**

Run: `pnpm test:product-image-preview`

Run: `pnpm build`

Run: `git diff --check`

Expected: all commands pass; the diff contains no unrelated generated files.
