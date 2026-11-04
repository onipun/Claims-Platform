import { DIALOG_DATA, Dialog, DialogModule, DialogRef } from '@angular/cdk/dialog';
import { Component, Injectable, inject, input } from '@angular/core';
import { LucideAngularModule, X } from 'lucide-angular';
import { UiButton } from './button';

/**
 * Dialog chrome (FleetROS dialog.tsx recipes). CDK Dialog supplies the focus trap, focus restore,
 * Escape, backdrop click and aria-modal; this component supplies title, description and the close button.
 * Use it as the root of a component opened with `Dialog.open(...)`.
 *
 * The visual panel (border, background, padding, width cap) lives on THIS host, not on `panelClass`.
 * CDK 22 renders the overlay pane inside a popover wrapper with `position: static` inline and its own
 * `max-width: 100%`, so Tailwind classes on the pane lost to it: the panel filled the viewport and the
 * absolutely positioned close button anchored to the viewport corner (F6). The pane only gets inline
 * `width`/`maxWidth` from the open call, which always win.
 *
 * Ids: the opener sets the dialog `id` to `<base>` and `ariaLabelledBy`/`ariaDescribedBy` to
 * `<base>-title` / `<base>-desc`, which are the ids rendered below (`dialogIds`).
 */
@Component({
  selector: 'ui-dialog-shell',
  imports: [LucideAngularModule],
  host: { class: 'relative grid gap-4 overflow-y-auto rounded-lg border bg-background p-6 text-foreground shadow-lg' },
  template: `
    <div class="flex flex-col space-y-1.5 text-center sm:text-left">
      <h2 [id]="titleId" class="text-lg font-semibold leading-none tracking-tight">{{ title() }}</h2>
      @if (description()) {
        <p [id]="descriptionId" class="text-sm text-muted-foreground">{{ description() }}</p>
      }
    </div>
    <div><ng-content /></div>
    <div class="flex flex-col-reverse sm:flex-row sm:justify-end sm:gap-2"><ng-content select="[dialogFooter]" /></div>
    <button
      type="button"
      class="absolute right-2 top-2 inline-flex h-8 w-8 items-center justify-center rounded-sm opacity-70 ring-offset-background transition-opacity hover:opacity-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
      (click)="ref.close()"
    >
      <lucide-icon [img]="closeIcon" class="h-4 w-4" aria-hidden="true" />
      <span class="sr-only">Close</span>
    </button>
  `,
})
export class UiDialogShell {
  readonly title = input.required<string>();
  readonly description = input<string>();
  protected readonly ref = inject(DialogRef);
  protected readonly closeIcon = X;
  protected readonly titleId = dialogIds(this.ref.id).title;
  protected readonly descriptionId = dialogIds(this.ref.id).description;
}

/** The ids the shell renders for a dialog opened with `id: base`. */
export function dialogIds(base: string): { title: string; description: string } {
  return { title: `${base}-title`, description: `${base}-desc` };
}

let dialogSeq = 0;

export interface ConfirmOptions {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
}

@Component({
  selector: 'ui-confirm-dialog',
  imports: [DialogModule, UiDialogShell, UiButton],
  template: `
    <ui-dialog-shell [title]="data.title" [description]="data.message">
      <ng-container dialogFooter>
        <button uiButton variant="outline" type="button" class="mt-2 sm:mt-0" [attr.cdkFocusInitial]="data.destructive ? '' : null" (click)="ref.close(false)">
          {{ data.cancelLabel ?? 'Cancel' }}
        </button>
        <button uiButton [variant]="data.destructive ? 'destructive' : 'default'" type="button" [attr.cdkFocusInitial]="data.destructive ? null : ''" (click)="ref.close(true)">
          {{ data.confirmLabel ?? 'Confirm' }}
        </button>
      </ng-container>
    </ui-dialog-shell>
  `,
})
export class UiConfirmDialog {
  protected readonly ref = inject<DialogRef<boolean>>(DialogRef);
  protected readonly data = inject<ConfirmOptions>(DIALOG_DATA);
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(Dialog);

  /** Resolves true on confirm, false on cancel / Escape / backdrop. */
  async confirm(options: ConfirmOptions): Promise<boolean> {
    const id = `confirm-dialog-${++dialogSeq}`;
    const ids = dialogIds(id);
    const ref = this.dialog.open<boolean, ConfirmOptions>(UiConfirmDialog, {
      id,
      data: options,
      width: 'calc(100% - 2rem)',
      maxHeight: 'calc(100dvh - 2rem)',
      maxWidth: '32rem',
      ariaLabelledBy: ids.title,
      ariaDescribedBy: options.message ? ids.description : null,
      backdropClass: ['bg-black/80'],
      ariaModal: true,
      role: 'alertdialog',
      // The element marked cdkFocusInitial gets focus: Cancel for a destructive dialog, the confirm button otherwise.
      autoFocus: 'first-tabbable',
    });
    return (await new Promise<boolean | undefined>((r) => ref.closed.subscribe(r))) === true;
  }
}
