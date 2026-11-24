import { useState } from 'react';
import { Box, Button } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import { fonts, tokens } from '../theme';

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** A copyable snippet (commands, configuration). */
export function CodeBlock({ code, label = 'Copy' }: { code: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Box sx={{ position: 'relative', bgcolor: tokens.ink, borderRadius: 1.5, my: 1 }}>
      <Box
        component="pre"
        sx={{
          m: 0,
          p: 2,
          pr: 11,
          overflowX: 'auto',
          color: '#E6EBF3',
          fontFamily: fonts.mono,
          fontSize: 12.5,
          lineHeight: 1.6,
        }}
      >
        {code}
      </Box>
      <Button
        size="small"
        onClick={async () => {
          if (await copyText(code)) {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1600);
          }
        }}
        startIcon={copied ? <CheckIcon fontSize="small" /> : <ContentCopyIcon fontSize="small" />}
        sx={{ position: 'absolute', top: 8, right: 8, color: '#E6EBF3', bgcolor: tokens.sidebarActive, '&:hover': { bgcolor: '#33425E' } }}
      >
        {copied ? 'Copied' : label}
      </Button>
    </Box>
  );
}
