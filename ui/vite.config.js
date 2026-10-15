import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import postcss from 'postcss'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname)
const sdkStyles = resolve(root, '../.integration/ui-sdk/shadow-styles.mjs')
if (!existsSync(sdkStyles)) {
  throw new Error('正式 UI SDK 缺失：' + sdkStyles + '；禁止从相邻宿主源码或开发目录取用')
}
const sdk = await import(sdkStyles)
if (typeof sdk.prepareShadowStyles !== 'function') throw new Error('正式 UI SDK 未导出 prepareShadowStyles(css, postcss)：' + sdkStyles)
const contract = JSON.parse(readFileSync(resolve(root, '../.integration/ui-sdk/contract.json'), 'utf8'))
const lock = JSON.parse(readFileSync(resolve(root, 'package-lock.json'), 'utf8'))
const componentPackage = lock.packages['node_modules/' + contract.designSystem.package]
if (contract.contractVersion !== 1 || componentPackage?.version !== contract.designSystem.version || componentPackage?.integrity !== contract.designSystem.integrity) {
  throw new Error('公开组件包与正式 UI SDK 的版本或 integrity 不匹配')
}
const shadowStyles = {
  name: 'rbac-shadow-styles',
  enforce: 'post',
  async generateBundle(options, bundle) {
    for (const asset of Object.values(bundle)) {
      if (asset.type === 'asset' && asset.fileName.endsWith('.css')) {
        asset.source = await sdk.prepareShadowStyles(String(asset.source), postcss)
      }
    }
  }
}

export default defineConfig({
  root,
  plugins: [vue(), shadowStyles],
  define: { 'process.env.NODE_ENV': JSON.stringify('production'), 'import.meta.env.NODE_ENV': JSON.stringify('production') },
  build: {
    outDir: resolve(root, '../build/generated-ui/ui-extensions/rbac'),
    emptyOutDir: true,
    cssCodeSplit: false,
    lib: { entry: resolve(root, 'src/index.js'), formats: ['es'], fileName: () => 'entry.js' },
    rollupOptions: { external: [] }
  }
})
