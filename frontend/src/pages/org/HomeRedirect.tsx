import { Navigate } from 'react-router-dom';
import { Box } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { orgApi } from '../../api/endpoints';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { lastOrganization } from '../../lib/storage';

/** "/" opens the organization used last, the first one otherwise, or the setup screen. */
export function HomeRedirect() {
  const organizations = useQuery({ queryKey: ['organizations'], queryFn: orgApi.list });
  if (organizations.isPending) {
    return (
      <Box sx={{ p: 4 }}>
        <Loading label="Loading your organizations" />
      </Box>
    );
  }
  if (organizations.isError) {
    return (
      <Box sx={{ p: 4 }}>
        <ErrorNotice error={organizations.error} onRetry={() => void organizations.refetch()} />
      </Box>
    );
  }
  const list = organizations.data;
  if (list.length === 0) return <Navigate to="/organizations/new" replace />;
  const last = lastOrganization();
  const target = list.find((o) => o.id === last) ?? list[0];
  return <Navigate to={`/o/${target.id}`} replace />;
}
