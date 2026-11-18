import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Each test file opts into a DOM with a `// @vitest-environment` comment.
    include: ['test/**/*.test.ts'],
    testTimeout: 20_000,
  },
});
