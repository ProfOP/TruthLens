export const ASSIGNABLE_ROLES = ['USER', 'FACT_CHECKER', 'ADMIN'] as const;

export type AdminUser = {
  id: string;
  name?: string;
  email?: string;
  role?: string;
  active?: boolean;
  created_at?: string;
};

export type RoleChoice = {role: string; disabled: boolean; reason: string};

/**
 * Mirrors the backend policy (AdminController.updateUser): no self-change, and at
 * least one active administrator must remain. The server enforces both regardless.
 */
export function roleChoices(target: AdminUser | null, actorId: string, users: AdminUser[]): RoleChoice[] {
  if (!target) return [];
  if (target.id === actorId) {
    return ASSIGNABLE_ROLES.map(role => ({role, disabled: true, reason: 'Administrators cannot change their own role or status.'}));
  }
  const isActiveAdmin = target.role === 'ADMIN' && target.active !== false;
  const otherActiveAdmins = users.filter(u => u.id !== target.id && u.role === 'ADMIN' && u.active !== false).length;
  return ASSIGNABLE_ROLES.map(role => {
    if (isActiveAdmin && role !== 'ADMIN' && otherActiveAdmins < 1) {
      return {role, disabled: true, reason: 'At least one active administrator must remain.'};
    }
    return {role, disabled: false, reason: ''};
  });
}

export function selfChangeBlocked(target: AdminUser | null, actorId: string) {
  return !!target && target.id === actorId;
}

export function saveBlockedReason(target: AdminUser | null, actorId: string, users: AdminUser[], draft: string) {
  if (!target || !draft) return 'Select a role to save.';
  if (selfChangeBlocked(target, actorId)) return 'You cannot change your own account role.';
  if (draft === target.role) return 'Choose a different role before saving.';
  const choice = roleChoices(target, actorId, users).find(c => c.role === draft);
  if (choice?.disabled) return choice.reason;
  return '';
}

export type ModelRow = {name: string; version: string; status: string; detail: string};

export type TypeOption = {value: string; label: string};

/** History / admin type filters: the empty value means "no type constraint sent". */
export const HISTORY_TYPE_OPTIONS: TypeOption[] = [
  {value: '', label: 'All types'},
  {value: 'TEXT', label: 'Text'},
  {value: 'IMAGE', label: 'Image'},
  {value: 'CLAIM', label: 'Claim'},
];

export function typeLabel(type?: string): string {
  return HISTORY_TYPE_OPTIONS.find(option => option.value === type)?.label || type || 'Unknown';
}

/** The backend echoes the capped page size, so the footer uses its numbers, not the request. */
export function historyPageFooter(page: number, size: number, total: number, rows: number): string {
  const capped = Math.min(Math.max(size, 1), 100);
  const first = total === 0 ? 0 : page * capped + 1;
  const last = page * capped + rows;
  return `Showing ${first}-${last} of ${total} · page ${page + 1}`;
}

export function hasNextHistoryPage(page: number, size: number, total: number, rows: number): boolean {
  return (page + 1) * Math.min(Math.max(size, 1), 100) < total || (rows >= Math.min(Math.max(size, 1), 100) && total === 0);
}

const VERSION_FIELDS = ['model', 'architecture', 'nli_model', 'embedding_model', 'retrieval_model'];

/** Version identifiers are read from existing AI metadata; absent values stay "Unavailable". */
export function modelRows(components: Record<string, any> | undefined | null): ModelRow[] {
  if (!components || typeof components !== 'object') return [];
  return Object.entries(components).map(([key, value]: [string, any]) => {
    const config = value?.config && typeof value?.config === 'object' ? value.config : {};
    const versions = VERSION_FIELDS.map(field => config[field]).filter(v => typeof v === 'string' && v.trim() !== '');
    const name = typeof value?.name === 'string' && value.name ? value.name : key;
    if (typeof config.iteration === 'number' && !name.toLowerCase().includes(`iteration ${config.iteration}`)) versions.push(`iteration ${config.iteration}`);
    if (typeof config.checkpoint === 'string' && config.checkpoint.trim()) versions.push(config.checkpoint);
    const unique = [...new Set(versions)];
    return {
      name,
      version: unique.length ? unique.join(' · ') : 'Unavailable',
      status: value?.available ? 'available' : 'unavailable',
      detail: typeof value?.artifact === 'string' ? value.artifact : '',
    };
  });
}
