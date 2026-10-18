import { createHash } from 'node:crypto'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const root = resolve(import.meta.dirname, '..')
const sdkStyles = resolve(root, '../.integration/ui-sdk/shadow-styles.mjs')
if (!existsSync(sdkStyles)) throw new Error('正式 UI SDK 缺失：' + sdkStyles + '；不能生成 manifest')
const check = spawnSync(process.execPath, [resolve(import.meta.dirname, 'check-translations.mjs')], { encoding: 'utf8' })
if (check.status !== 0) { process.stderr.write(check.stderr); process.exit(check.status || 1) }
const translation = JSON.parse(check.stdout)
const output = process.env.UI_OUTPUT_DIR ? resolve(process.env.UI_OUTPUT_DIR) : resolve(root, '../build/generated-ui/ui-extensions/rbac')
async function walk(directory, prefix = '') {
  const entries = await readdir(directory, { withFileTypes: true })
  const result = []
  for (const entry of entries) {
    const relative = prefix ? prefix + '/' + entry.name : entry.name
    if (entry.isDirectory()) result.push(...await walk(resolve(directory, entry.name), relative))
    else result.push(relative)
  }
  return result
}
const names = await walk(output)
const files = {}
for (const name of names) {
  if (name === 'manifest.json') continue
  const path = resolve(output, name)
  const data = await readFile(path)
  const mime = name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'application/javascript' : 'application/octet-stream'
  files[name] = { sha256: createHash('sha256').update(data).digest('hex'), mime }
}
const styles = Object.keys(files).filter(name => name.endsWith('.css')).sort()
const manifest = {
  contractVersion: 1,
  id: 'rbac',
  resourceRoot: 'ui-extensions/rbac',
  entry: 'entry.js',
  styles,
  files,
  titles: translation.titles,
  capabilityPath: '/api/v1/main/extensions/rbac/capabilities'
}
await writeFile(resolve(output, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
