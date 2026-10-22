import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { existsSync } from 'node:fs'

const directory = resolve(import.meta.dirname, '../src/translations')
const sdkPath = resolve(import.meta.dirname, '../../.integration/ui-sdk/check-translations.mjs')
if (!existsSync(sdkPath)) throw new Error('正式 UI SDK 缺失：' + sdkPath)
const sdk = await import(sdkPath)
if (typeof sdk.checkTranslations !== 'function') throw new Error('正式 UI SDK 缺少 checkTranslations(directory)')
const files = (await readdir(directory)).filter(name => name.endsWith('.json')).sort()
if (files.length !== 13) throw new Error('翻译文件数量必须为 13，实际为 ' + files.length)
const required = ["title", "applicationGroups", "users", "scopes", "clients", "assignments", "overview", "scope", "key", "roleName", "save", "kestraActualPermissions", "search", "reload", "conflict"]
// Check literal UI lookups too: matching locale key sets alone can miss a key
// absent from every language. Dynamic status/column keys remain in the catalogue.
const sourceDirectory = resolve(import.meta.dirname, '../src')
for (const file of await readdir(sourceDirectory)) {
  if (!/\.(vue|js)$/.test(file)) continue
  const source = await readFile(resolve(sourceDirectory, file), 'utf8')
  for (const match of source.matchAll(/\bt\(['"]([A-Za-z][A-Za-z0-9_]*)['"]/g)) {
    if (!required.includes(match[1])) required.push(match[1])
  }
}
const titles = {}
for (const file of files) {
  const data = JSON.parse(await readFile(resolve(directory, file), 'utf8'))
  const locale = Object.keys(data)[0]
  if (!locale || !data[locale] || required.some(key => typeof data[locale][key] !== 'string')) throw new Error('翻译缺键或格式错误：' + file)
  titles[locale] = data[locale].title
}
const result = await sdk.checkTranslations(directory)
if (!result.passed) throw new Error('正式 SDK 翻译完整性检查失败：' + JSON.stringify(result.reports))
process.stdout.write(JSON.stringify({ files, titles }) + '\n')
