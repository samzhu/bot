import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideCopilotKit } from '@copilotkit/angular';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    /*
     * RATIONALE: CopilotKit Angular 0.6 DI tree requires COPILOT_KIT_CONFIG when CopilotChatInput
     * injects the root-provided CopilotKit service.
     * `provideCopilotKit({ enableInspector: false })` satisfies this dependency without supplying a runtimeUrl.
     * Network request auditing during real browser testing confirmed 0 calls to Copilot endpoints.
     */
    provideCopilotKit({ enableInspector: false })
  ]
};
