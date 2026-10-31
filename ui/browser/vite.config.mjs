import {mergeConfig} from 'vite'
import base from '../vite.config.js'
import {resolve} from 'node:path'
export default mergeConfig(base, {
  build: {outDir: resolve(import.meta.dirname, '../../.work/plugin-browser/harness'), lib: false,
    rollupOptions: {input: resolve(import.meta.dirname, 'index.html'), output: {entryFileNames: 'harness.js'}}}
})
