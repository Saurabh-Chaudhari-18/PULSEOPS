import { Link as RouterLink } from 'react-router-dom';
import { Box, Button, Typography } from '@mui/material';

export function NotFoundPage() {
  return (
    <Box sx={{ py: 6, px: 3, maxWidth: 520 }}>
      <Typography variant="h1">Page not found</Typography>
      <Typography sx={{ mt: 1, mb: 3 }}>The address may be mistyped, or the page has moved.</Typography>
      <Button variant="contained" component={RouterLink} to="/">
        Go to PulseOps
      </Button>
    </Box>
  );
}
