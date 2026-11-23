type TableLinework = {
  outline?: string
  feature: string
  support?: string
}

/** Interior details in the same 100 × 100 coordinates as the PDF outlines. */
export const tableFurnitureLinework: Partial<Record<string, TableLinework>> = {
  // Wall edge, bar foot rail, standing pedestal, and observer modesty panel.
  T13: { feature: 'M 0 22 H 100' },
  T14: { feature: 'M 14 47 V 76 H 86 V 47' },
  T15: { feature: 'M 25 31 H 75 Q 80 31 80 41 V 59 Q 80 69 75 69 H 25 Q 20 69 20 59 V 41 Q 20 31 25 31 Z', support: 'M 30 50 H 70' },
  T16: { feature: 'M 6 30 H 94 V 51 H 6 Z' },
  // Fixed two-person work positions, then the flip axis and four caster marks.
  T17: { feature: 'M 8 28 V 68 H 42 V 28 M 58 28 V 68 H 92 V 28' },
  T18: { feature: 'M 14 50 H 86 M 14 22 H 23 V 37 H 14 Z M 77 22 H 86 V 37 H 77 Z M 14 63 H 23 V 78 H 14 Z M 77 63 H 86 V 78 H 77 Z' },
  T20: { feature: 'M 8 0 V 100 M 92 0 V 100' },
  'coffee-rect': {
    outline: 'M 4 0 H 96 Q 100 0 100 8 V 92 Q 100 100 96 100 H 4 Q 0 100 0 92 V 8 Q 0 0 4 0 Z',
    feature: 'M 13 24 V 76 M 87 24 V 76',
    support: 'M 13 50 H 87',
  },
  'coffee-round': {
    outline: 'M 50 0 A 50 50 0 1 1 50 100 A 50 50 0 1 1 50 0 Z',
    feature: 'M 50 15 A 35 35 0 1 1 50 85 A 35 35 0 1 1 50 15 Z',
  },
  'table-tea-rect': {
    outline: 'M 5 0 H 95 Q 100 0 100 10 V 90 Q 100 100 95 100 H 5 Q 0 100 0 90 V 10 Q 0 0 5 0 Z',
    feature: 'M 15 26 H 55 Q 59 26 59 34 V 66 Q 59 74 55 74 H 15 Q 11 74 11 66 V 34 Q 11 26 15 26 Z M 23 39 V 61 M 35 39 V 61 M 47 39 V 61 M 77 39 a 7 14 0 1 0 0 28 a 7 14 0 1 0 0 -28 Z',
  },
  'table-dining-rect': {
    outline: 'M 7 0 H 93 Q 100 0 100 12 V 88 Q 100 100 93 100 H 7 Q 0 100 0 88 V 12 Q 0 0 7 0 Z',
    feature: 'M 27 14 a 8 13 0 1 0 0 26 a 8 13 0 1 0 0 -26 Z M 73 14 a 8 13 0 1 0 0 26 a 8 13 0 1 0 0 -26 Z M 27 60 a 8 13 0 1 0 0 26 a 8 13 0 1 0 0 -26 Z M 73 60 a 8 13 0 1 0 0 26 a 8 13 0 1 0 0 -26 Z',
  },
  'table-game': {
    outline: 'M 7 0 H 93 L 100 14 V 86 L 93 100 H 7 L 0 86 V 14 Z',
    feature: 'M 14 19 H 86 L 91 28 V 72 L 86 81 H 14 L 9 72 V 28 Z M 38 37 L 48 29 L 56 59 L 46 67 Z M 54 44 L 65 38 L 70 69 L 59 75 Z',
  },
}
