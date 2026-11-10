# TestApp

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.0.0.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

### Bundle & Style Budgets (Ticket 02 / Issue #3 Rationale)
- `initial`: `maximumWarning: 5.0MB`, `maximumError: 5.5MB` (measured initial bundle is ~4.85 MB due to CopilotKit runtime modules).
- `anyComponentStyle`: `maximumWarning: 7kB`, `maximumError: 8kB`. Measured compiled `app.css` is 6.32 kB (accommodating monochrome stop button, interrupted status badge, and bounded pagination controls). Budget warning is set to 7 kB (actual + small margin) with error kept strictly at 8 kB to prevent regressions without unjustified looseness.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
