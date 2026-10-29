import {mkdir,readdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawn} from 'node:child_process'

const ui=fileURLToPath(new URL('..',import.meta.url))
const output=resolve(ui,'../.evidence/ui/results.xml')
await mkdir(resolve(ui,'../.evidence/ui'),{recursive:true})
const tests=(await readdir(resolve(ui,'test'))).filter(name=>name.endsWith('.test.mjs')).sort().map(name=>resolve(ui,'test',name))
if(!tests.length)throw new Error('UI test matrix is empty')
const child=spawn(process.execPath,['--test','--test-reporter=spec','--test-reporter=junit','--test-reporter-destination=stdout','--test-reporter-destination='+output,...tests],{cwd:ui,stdio:'inherit'})
child.on('error',error=>{console.error(error.name+': UI test runner could not start');process.exitCode=1})
child.on('exit',(code,signal)=>{process.exitCode=signal?1:code??1})
