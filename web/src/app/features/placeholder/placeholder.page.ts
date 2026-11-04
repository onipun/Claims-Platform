import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { UI_CARD } from '../../shared/ui/card';

/** Stand-in for screens built in later tasks. Title and blurb come from route data. */
@Component({
  selector: 'app-placeholder-page',
  imports: [UI_CARD],
  template: `
    <h1 class="mb-6 text-2xl font-bold tracking-tight">{{ title }}</h1>
    <section uiCard>
      <div uiCardHeader>
        <h2 uiCardTitle class="text-lg">Coming soon</h2>
        <p uiCardDescription>{{ blurb }}</p>
      </div>
    </section>
  `,
})
export class PlaceholderPage {
  private readonly data = inject(ActivatedRoute).snapshot.data;
  protected readonly title: string = this.data['title'] ?? 'Page';
  protected readonly blurb: string = this.data['blurb'] ?? 'This screen is built in a later task.';
}
