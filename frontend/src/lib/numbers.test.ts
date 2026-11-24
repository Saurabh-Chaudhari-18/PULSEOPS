import { describe, expect, it } from 'vitest';
import { fmtBytes, fmtCount, fmtMs, fmtPct, fmtRate } from './numbers';

describe('number formats', () => {
  it('never shows absent data as zero', () => {
    expect(fmtMs(null)).toBe('—');
    expect(fmtPct(undefined)).toBe('—');
    expect(fmtRate(null)).toBe('—');
  });

  it('formats latency at every scale', () => {
    expect(fmtMs(0.74)).toBe('0.7 ms');
    expect(fmtMs(48.4)).toBe('48 ms');
    expect(fmtMs(1514)).toBe('1.51 s');
    expect(fmtMs(12_500)).toBe('12.5 s');
  });

  it('formats rates and percentages', () => {
    expect(fmtPct(0)).toBe('0%');
    expect(fmtPct(0.0004)).toBe('<0.1%');
    expect(fmtPct(0.024)).toBe('2.4%');
    expect(fmtPct(0.75)).toBe('75%');
    expect(fmtRate(0.05)).toBe('3.0 req/min');
    expect(fmtRate(2.345)).toBe('2.3 req/s');
    expect(fmtRate(42.6, 'calls')).toBe('43 calls/s');
  });

  it('compacts counts and bytes', () => {
    expect(fmtCount(1284)).toBe('1,284');
    expect(fmtCount(12_900)).toBe('12.9K');
    expect(fmtCount(4_200_000)).toBe('4.2M');
    expect(fmtBytes(512)).toBe('512 B');
    expect(fmtBytes(268_435_456)).toBe('256 MB');
  });
});
