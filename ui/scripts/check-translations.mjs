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
const required = ['title', 'roles', 'users', 'bindings', 'name', 'description', 'save', 'create', 'delete', 'cancel', 'back', 'subject', 'password', 'email', 'active', 'inactive', 'reset', 'roleIds', 'trustedRole', 'resource', 'actions', 'namespace', 'children', 'allIds', 'ids', 'revision', 'loading', 'error', 'saved', 'confirm', 'readOnly', 'denied', 'noRows', 'protected', 'edit', 'reload', 'conflict', 'policyMode', 'next', 'previous', 'addGrant', 'removeGrant', 'bindingSubject', 'bindingRole']
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
