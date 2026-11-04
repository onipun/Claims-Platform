import { UiBadge } from './badge';
import { UiButton } from './button';
import { UI_CARD } from './card';
import { UiInput, UiSelect, UiTextarea } from './controls';
import { UiFormField, UiLabel, UiRequiredLegend } from './form-field';
import { UiEmptyState, UiErrorState, UiWarningNotice } from './notices';
import { UiSkeleton, UiSkeletonCard, UiSkeletonTable, UiSkeletonText } from './skeleton';
import { UI_TABLE } from './table';
import { UiTabs } from './tabs';
import { ClaimStatusBadge } from './claim-status-badge';
import { MoneyPipe } from './money';

export * from './announcer';
export * from './badge';
export * from './button';
export * from './card';
export * from './claim-status-badge';
export * from './controls';
export * from './dialog';
export * from './form-field';
export * from './money';
export * from './notices';
export * from './skeleton';
export * from './table';
export * from './tabs';
export * from './toast';

/** Everything a feature template needs, in one import. */
export const UI = [
  UiBadge, UiButton, ...UI_CARD, UiInput, UiTextarea, UiSelect, UiLabel, UiFormField, UiRequiredLegend,
  UiWarningNotice, UiErrorState, UiEmptyState, UiSkeleton, UiSkeletonText, UiSkeletonTable, UiSkeletonCard,
  ...UI_TABLE, UiTabs, ClaimStatusBadge, MoneyPipe,
] as const;
