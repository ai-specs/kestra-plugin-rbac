import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {manifest} from './matrix.mjs'
const output=process.argv[2] ? resolve(process.argv[2]) : resolve(import.meta.dirname,'../../.evidence/ui-browser')
const report=JSON.parse(readFileSync(resolve(output,'report.json')))
const actualManifest=JSON.parse(readFileSync(resolve(output,'manifest.json')))
assert.deepEqual(actualManifest,manifest,'Exact named browser matrix must match this source')
assert.equal(report.schemaVersion,1);assert.equal(report.gate,manifest.gate)
assert.equal(report.gatePassed,true,'Browser gate must pass without missing cases')
assert.deepEqual(report.summary,{planned:720,executed:720,passed:720,failed:0,notRun:0,skipped:0})
assert.deepEqual(report.cases.map(row=>row.id),manifest.cases.map(row=>row.id))
for(const row of report.cases){assert.equal(row.status,'passed',row.id);assert.deepEqual(row.errors,[],row.id)}
for(const key of ['hostExecutableSha256','sourceSha256','lockSha256','bundleSha256','sdkSha256'])assert.match(report.inputs[key],/^[a-f0-9]{64}$/)
assert.match(report.inputs.sourceCommit,/^[a-f0-9]{40}$/)
assert.match(report.inputs.image,/^ghcr.io\/ai-specs\/kestra-overlay@sha256:[a-f0-9]{64}$/)
for(const key of ['image','hostExecutableSha256','sourceCommit','sourceSha256','lockSha256','bundleSha256','sdkSha256'])assert.equal(report.finalInputs[key],report.inputs[key],'Final input mismatch: '+key)
assert.deepEqual(report.errors,[])
assert.equal(report.inputs.jar.equal,true);assert.equal(report.inputs.jar.jarBundleSha256,report.inputs.bundleSha256);assert.equal(report.inputs.jar.browserBundleSha256,report.inputs.bundleSha256)
console.log(JSON.stringify({gate:report.gate,...report.summary,gatePassed:true}))
