import type { ReactNode } from 'react';
import { Box, Typography } from '@mui/material';
import { PulseMark } from '../components/PulseMark';
import { tokens } from '../theme';

/**
 * The trace on the sign-in screens: a steady rhythm, one irregular beat, then steady again,
 * which is what the product exists to notice. It is drawn once on load (the only ambient motion
 * in the app); reduced-motion users get it already drawn.
 */
const TRACE =
  'M0 120 H140 l10 -22 l12 44 l10 -62 l12 70 l10 -30 H330 l10 -22 l12 44 l10 -62 l12 70 l10 -30 ' +
  'H520 l8 -16 l10 32 l9 -96 l11 150 l9 -94 l9 24 H700 l10 -22 l12 44 l10 -62 l12 70 l10 -30 H900';

function Trace() {
  return (
    <Box
      component="svg"
      viewBox="0 0 900 240"
      preserveAspectRatio="none"
      aria-hidden
      sx={{ width: '100%', height: { xs: 120, md: 220 }, display: 'block' }}
    >
      <path d="M0 120 H900" stroke="#2A3750" strokeWidth="1" />
      <Box
        component="path"
        d={TRACE}
        fill="none"
        stroke={tokens.pulse}
        strokeWidth={2.2}
        strokeLinejoin="round"
        strokeLinecap="round"
        pathLength={1}
        sx={{
          strokeDasharray: 1,
          strokeDashoffset: 0,
          animation: 'pulseops-draw 2.2s cubic-bezier(.4,0,.2,1) both',
          '@keyframes pulseops-draw': { from: { strokeDashoffset: 1 }, to: { strokeDashoffset: 0 } },
        }}
      />
      <circle cx="547" cy="40" r="5" fill="none" stroke={tokens.critical} strokeWidth="2" />
    </Box>
  );
}

export function AuthLayout({ title, intro, children }: { title: string; intro?: ReactNode; children: ReactNode }) {
  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1.1fr) minmax(420px, 1fr)' },
      }}
    >
      <Box
        sx={{
          bgcolor: tokens.ink,
          color: tokens.sidebarText,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          p: { xs: 3, md: 6 },
          gap: 4,
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
          <PulseMark size={32} />
          <Typography sx={{ color: '#FFFFFF', fontWeight: 600, fontSize: 18 }}>PulseOps</Typography>
        </Box>
        <Box>
          <Trace />
          <Typography
            sx={{
              color: '#FFFFFF',
              fontSize: { xs: 22, md: 30 },
              fontWeight: 500,
              lineHeight: 1.25,
              mt: 3,
              maxWidth: '22ch',
            }}
          >
            Know when something changes, and why.
          </Typography>
          <Typography sx={{ color: '#9AA7BD', mt: 1.5, maxWidth: '46ch' }}>
            Traces, logs, database calls and metrics from your services, watched every minute and turned into incidents
            you can act on.
          </Typography>
        </Box>
        <Typography sx={{ color: '#6F7D95', fontSize: 12, display: { xs: 'none', md: 'block' } }}>
          Self-hosted or SaaS. Your data stays in your organization.
        </Typography>
      </Box>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          p: { xs: 3, md: 6 },
          bgcolor: 'background.paper',
        }}
      >
        <Box sx={{ width: '100%', maxWidth: 380 }}>
          <Typography variant="h1" component="h1">
            {title}
          </Typography>
          {intro && (
            <Typography color="text.secondary" sx={{ mt: 0.75 }}>
              {intro}
            </Typography>
          )}
          <Box sx={{ mt: 3 }}>{children}</Box>
        </Box>
      </Box>
    </Box>
  );
}
