import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Select,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { errorMessage } from '../../api/client';
import { orgApi } from '../../api/endpoints';
import type { Member, Role } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorNotice, Loading } from '../../components/Feedback';
import { PageHeader } from '../../components/PageHeader';
import { P, useOrg } from '../../layout/OrgContext';
import { formatDate, roleDescription, roleLabel } from '../../lib/format';
import { useFormError } from '../../lib/useFormError';

const ROLES: Role[] = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];

function AddMemberDialog({ orgId, open, onClose, onAdded }: { orgId: string; open: boolean; onClose: () => void; onAdded: () => void }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<Role>('MEMBER');
  const [busy, setBusy] = useState(false);
  const form = useFormError();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    form.clear();
    try {
      await orgApi.addMember(orgId, email.trim(), role);
      setEmail('');
      onAdded();
    } catch (e) {
      form.setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={() => !busy && onClose()} maxWidth="sm" fullWidth>
      <Box component="form" onSubmit={submit} noValidate>
        <DialogTitle>Add a member</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {form.general && <Alert severity="error">{form.general}</Alert>}
          <TextField
            label="Email of their PulseOps account"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={!!form.field('email')}
            helperText={form.field('email') ?? 'They need to have created an account first.'}
            autoFocus
            required
          />
          <TextField select label="Role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.map((r) => (
              <MenuItem key={r} value={r}>
                <Box>
                  <Typography variant="h4">{roleLabel[r]}</Typography>
                  <Typography variant="caption">{roleDescription[r]}</Typography>
                </Box>
              </MenuItem>
            ))}
          </TextField>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={busy || !email.trim()}>
            Add member
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}

export function TeamPage() {
  const { organization, can, access } = useOrg();
  const { user } = useAuth();
  const orgId = organization.id;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Member | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [roleError, setRoleError] = useState<unknown>(null);

  const members = useQuery({ queryKey: ['members', orgId], queryFn: () => orgApi.members(orgId) });
  const canManage = can(P.membersManage);
  const isOwner = access.role === 'OWNER';
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['members', orgId] });

  // Only an owner can grant, change or remove the owner role.
  const editable = (m: Member) => canManage && m.userId !== user?.id && (isOwner || m.role !== 'OWNER');

  return (
    <>
      <PageHeader
        title="Team"
        description={`People who can see ${organization.name}. What they can change depends on their role.`}
        actions={
          <>
            {canManage && (
              <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAdding(true)}>
                Add member
              </Button>
            )}
            <Button onClick={() => setLeaving(true)}>Leave organization</Button>
          </>
        }
      />
      {roleError !== null && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setRoleError(null)}>
          {errorMessage(roleError)}
        </Alert>
      )}
      {members.isError && <ErrorNotice error={members.error} onRetry={() => void members.refetch()} />}
      {members.isPending ? (
        <Loading label="Loading team" />
      ) : (
        members.data && (
          <TableContainer component={Paper} variant="outlined">
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Name</TableCell>
                  <TableCell>Role</TableCell>
                  <TableCell>Joined</TableCell>
                  <TableCell align="right" />
                </TableRow>
              </TableHead>
              <TableBody>
                {members.data.content
                  .filter((m) => m.status !== 'REMOVED')
                  .map((m) => (
                    <TableRow key={m.memberId}>
                      <TableCell>
                        <Typography variant="h4" component="div">
                          {m.fullName}
                          {m.userId === user?.id && (
                            <Typography component="span" variant="caption">
                              {' '}
                              (you)
                            </Typography>
                          )}
                        </Typography>
                        <Typography variant="caption">{m.email}</Typography>
                      </TableCell>
                      <TableCell sx={{ minWidth: 160 }}>
                        {editable(m) ? (
                          <Select
                            size="small"
                            value={m.role}
                            inputProps={{ 'aria-label': `Role of ${m.fullName}` }}
                            onChange={async (e) => {
                              setRoleError(null);
                              try {
                                await orgApi.changeRole(orgId, m.memberId, e.target.value as Role);
                                refresh();
                              } catch (err) {
                                setRoleError(err);
                              }
                            }}
                          >
                            {ROLES.filter((r) => isOwner || r !== 'OWNER').map((r) => (
                              <MenuItem key={r} value={r}>
                                {roleLabel[r]}
                              </MenuItem>
                            ))}
                          </Select>
                        ) : (
                          roleLabel[m.role] ?? m.role
                        )}
                      </TableCell>
                      <TableCell>{m.status === 'INVITED' ? 'Invited' : formatDate(m.joinedAt)}</TableCell>
                      <TableCell align="right">
                        {editable(m) && (
                          <Button size="small" color="error" onClick={() => setRemoving(m)}>
                            Remove
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          </TableContainer>
        )
      )}

      <AddMemberDialog
        orgId={orgId}
        open={adding}
        onClose={() => setAdding(false)}
        onAdded={() => {
          setAdding(false);
          refresh();
        }}
      />
      <ConfirmDialog
        open={!!removing}
        title={`Remove ${removing?.fullName}?`}
        confirmLabel="Remove member"
        destructive
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          if (removing) await orgApi.removeMember(orgId, removing.memberId);
          refresh();
        }}
      >
        They lose access to {organization.name} immediately. You can add them again later.
      </ConfirmDialog>
      <ConfirmDialog
        open={leaving}
        title={`Leave ${organization.name}?`}
        confirmLabel="Leave organization"
        destructive
        onClose={() => setLeaving(false)}
        onConfirm={async () => {
          await orgApi.leave(orgId);
          await queryClient.invalidateQueries({ queryKey: ['organizations'] });
          navigate('/', { replace: true });
        }}
      >
        You lose access immediately. An admin can add you again. The last owner cannot leave.
      </ConfirmDialog>
    </>
  );
}
