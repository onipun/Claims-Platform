import { RouterOutlet } from '@angular/router';
import { Component, inject } from '@angular/core';
import { RouteFocus } from './layout/route-focus';
import { UiToastHost } from './shared/ui/toast';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, UiToastHost],
  template: `
    <router-outlet />
    <ui-toast-host />
  `,
})
export class App {
  constructor() {
    inject(RouteFocus);
  }
}
