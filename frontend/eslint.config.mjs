import angularTemplate from '@angular-eslint/eslint-plugin-template';
import * as angularTemplateParser from '@angular-eslint/template-parser';
import { defineConfig } from 'eslint/config';
import tseslint from 'typescript-eslint';

const threadPlaneInternals = {
  // These are the public runtime and test subpaths in the locked ThreadPlane packages.
  regex: String.raw`^@threadplane/(?!(?:chat/(?:chat\.css|themes/(?:default|material)-(?:dark|light)\.css|debug|testing)|telemetry/(?:browser|node|shared))$)[^/]+/`,
  message: 'Import from the ThreadPlane package root or a public subpath.',
};

const relativeAppPath = String.raw`^(?:\./)*(?:\.\./(?:\./)*)+(?:app/(?:\./)*)?`;
const appComposition = {
  regex: relativeAppPath + String.raw`(?:app|app\.config|app\.routes)(?:\.[cm]?[jt]s)?$`,
  message: 'App owns composition. Use a feature interface or pass data through inputs and outputs.',
};

const restrictImports = (...patterns) => [
  'error',
  { patterns: [threadPlaneInternals, ...patterns] },
];

export default defineConfig([
  { linterOptions: { reportUnusedDisableDirectives: 'error' } },
  {
    files: ['src/**/*.ts'],
    languageOptions: { parser: tseslint.parser },
    plugins: { '@typescript-eslint': tseslint.plugin },
    processor: angularTemplate.createProcessor(),
    rules: { '@typescript-eslint/no-restricted-imports': restrictImports() },
  },
  {
    files: ['src/app/shell/**/*.ts', 'src/app/bot/**/*.ts'],
    ignores: ['**/*.spec.ts'],
    rules: { '@typescript-eslint/no-restricted-imports': restrictImports(appComposition) },
  },
  {
    files: ['src/app/bot/**/*.ts'],
    ignores: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-restricted-imports': restrictImports(appComposition, {
        regex: relativeAppPath + 'shell(?:/|$)',
        message: 'App composes Bot and the navigation shell. Keep Bot independent of the shell.',
      }),
    },
  },
  {
    files: ['src/**/*.html'],
    languageOptions: { parser: angularTemplateParser },
    plugins: { '@angular-eslint/template': angularTemplate },
    rules: {
      '@angular-eslint/template/alt-text': 'error',
      '@angular-eslint/template/label-has-associated-control': 'error',
    },
  },
]);
