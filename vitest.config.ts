import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['node/packages/*/test/**/*.test.ts', 'tools/*/test/**/*.test.ts', 'examples/test/**/*.test.ts'],
    testTimeout: 15000,
    pool: 'threads',
  },
});
