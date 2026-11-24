import { useState } from 'react';
import { ApiError, errorMessage } from '../api/client';

/** Splits an API failure into per-field messages and one general message for the form. */
export function useFormError() {
  const [error, setError] = useState<unknown>(null);
  const field = (name: string) => (error instanceof ApiError ? error.fieldMessage(name) : undefined);
  const general = error === null ? null : error instanceof ApiError && error.fieldErrors.length > 0 ? null : errorMessage(error);
  return { error, setError, field, general, clear: () => setError(null) };
}
