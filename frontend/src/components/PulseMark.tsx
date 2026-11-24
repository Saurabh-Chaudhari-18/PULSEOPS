import { Box } from '@mui/material';
import { tokens } from '../theme';

/** The product mark: a single heartbeat on an ink tile. */
export function PulseMark({ size = 28 }: { size?: number }) {
  return (
    <Box component="svg" viewBox="0 0 32 32" sx={{ width: size, height: size, flexShrink: 0 }} aria-hidden>
      <rect width="32" height="32" rx="7" fill={tokens.sidebarActive} />
      <path
        d="M4 17h6l2.5-6 4 12 3-9 1.5 3H28"
        fill="none"
        stroke={tokens.pulse}
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Box>
  );
}
