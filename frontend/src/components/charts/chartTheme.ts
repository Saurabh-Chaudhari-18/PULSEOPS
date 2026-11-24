/**
 * Series colours, in fixed order, validated for colour-vision deficiency on the white chart
 * surface (dataviz validator: adjacent CVD ΔE ≥ 9.2, normal-vision ΔE ≥ 27.6). Slot 3 sits below
 * 3:1 contrast, so every chart has a legend and a table view. Health colours (theme tokens) are
 * never used for series.
 */
export const SERIES = ['#2a78d6', '#eb6834', '#1baf7a'] as const;

export const chartInk = {
  grid: '#E6EAF0',
  axis: '#C9D1DC',
  tick: '#6B778A',
};
