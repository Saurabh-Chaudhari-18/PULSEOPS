import { createTheme } from '@mui/material/styles';

/**
 * Design tokens. PulseOps is an instrument: flat bordered panels, no decorative shadows or
 * gradients, and colour reserved for meaning. Cobalt is the only "brand" colour and marks
 * what you can act on; green/amber/red appear only when they describe the health of something.
 */
export const tokens = {
  ink: '#172033', // sidebar, headings, primary text
  inkSoft: '#3A465B', // body text
  muted: '#6B778A', // secondary text, unknown state
  paper: '#F3F5F8', // app canvas
  surface: '#FFFFFF', // panels
  rule: '#D9DFE7', // borders and dividers
  ruleStrong: '#BCC5D1',
  cobalt: '#2E5BDA', // actions, links, focus
  cobaltSoft: '#E8EEFC',
  ok: '#18875F',
  watch: '#B7791F',
  critical: '#C8372D',
  sidebarText: '#C5CEDD',
  sidebarActive: '#25324A',
  pulse: '#7FA2FF', // the pulse line in the mark and on the sign-in screen
};

export const fonts = {
  sans: '"IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
  mono: '"IBM Plex Mono", ui-monospace, "Cascadia Mono", Consolas, monospace',
};

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: tokens.cobalt, contrastText: '#FFFFFF' },
    secondary: { main: tokens.ink },
    success: { main: tokens.ok },
    warning: { main: tokens.watch },
    error: { main: tokens.critical },
    text: { primary: tokens.ink, secondary: tokens.muted },
    divider: tokens.rule,
    background: { default: tokens.paper, paper: tokens.surface },
  },
  shape: { borderRadius: 6 },
  typography: {
    fontFamily: fonts.sans,
    fontSize: 14,
    // Scale 12 / 13 / 14 / 16 / 20 / 26: small steps for dense data, one clear jump for page titles.
    h1: { fontSize: 26, fontWeight: 600, lineHeight: 1.25, letterSpacing: '-0.01em' },
    h2: { fontSize: 20, fontWeight: 600, lineHeight: 1.3 },
    h3: { fontSize: 16, fontWeight: 600, lineHeight: 1.4 },
    h4: { fontSize: 14, fontWeight: 600, lineHeight: 1.4 },
    body1: { fontSize: 14, lineHeight: 1.55, color: tokens.inkSoft },
    body2: { fontSize: 13, lineHeight: 1.5, color: tokens.inkSoft },
    caption: { fontSize: 12, lineHeight: 1.45, color: tokens.muted },
    button: { textTransform: 'none', fontWeight: 600, fontSize: 14 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: tokens.paper },
        'code, pre, kbd': { fontFamily: fonts.mono },
        ':focus-visible': { outline: `2px solid ${tokens.cobalt}`, outlineOffset: 2 },
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': { animationDuration: '0.01ms !important', transitionDuration: '0.01ms !important' },
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { borderRadius: 6, paddingInline: 14 } },
    },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: { root: { backgroundImage: 'none' }, outlined: { borderColor: tokens.rule } },
    },
    MuiDialog: {
      styleOverrides: { paper: { borderRadius: 8, border: `1px solid ${tokens.rule}` } },
    },
    MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: tokens.rule, fontSize: 13, paddingTop: 10, paddingBottom: 10 },
        head: { color: tokens.muted, fontWeight: 600, fontSize: 12, backgroundColor: tokens.paper },
      },
    },
    MuiChip: { styleOverrides: { root: { borderRadius: 4, fontWeight: 500 } } },
    MuiTooltip: { styleOverrides: { tooltip: { backgroundColor: tokens.ink, fontSize: 12 } } },
    MuiLink: { defaultProps: { underline: 'hover' } },
  },
});
