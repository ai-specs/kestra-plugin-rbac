import {spawnSync} from 'node:child_process'
import {readFileSync,writeFileSync,mkdirSync,rmSync} from 'node:fs'
import {resolve} from 'node:path'
import {chromium} from '@playwright/test'
import {manifest} from './matrix.mjs'
import {repo,hashFile,hashes,aggregate,currentInputs,inputChanges} from './provenance.mjs'
const output=resolve(repo,'.evidence/ui-browser')
mkdirSync(output,{recursive:true})
rmSync(resolve(output,'report.json'),{force:true})
const inputs={image:'',hostExecutableSha256:'',sourceCommit:'',sourceSha256:'',lockSha256:'',bundleSha256:'',sdkSha256:'',
  transport:'in-memory management API and SDK context; no production authentication, storage, authorization',
  playwright:JSON.parse(readFileSync(resolve(repo,'ui/node_modules/@playwright/test/package.json'))).version}
const write=(name,value)=>writeFileSync(resolve(output,name),JSON.stringify(value,null,2)+'\n')
function setupFailure(error){
  let finalInputs
  try{finalInputs=currentInputs()}catch{}
  write('inputs.json',inputs);write('manifest.json',manifest)
  write('report.json',{schemaVersion:1,gate:manifest.gate,gatePassed:false,inputs,finalInputs,
    errors:[String(error)],summary:{planned:manifest.cases.length,executed:0,passed:0,failed:0,notRun:manifest.cases.length,skipped:0},
    cases:manifest.cases.map(row=>({...row,status:'notRun',durationMs:0,errors:[],attachments:[]}))})
}
function run(args,env={}){
  const result=spawnSync(process.execPath,args,{cwd:resolve(repo,'ui'),stdio:'inherit',env:{...process.env,...env}})
  if(result.error || result.status!==0)throw new Error('Failed: '+args.join(' ')+': '+(result.error || result.status))
}
try {
  const before=currentInputs({bundle:false});Object.assign(inputs,before)
  run(['scripts/check-translations.mjs'])
  run(['node_modules/vite/bin/vite.js','build'],{UI_OUTPUT_DIR:resolve(repo,'.work/plugin-browser/bundle')})
  run(['node_modules/vite/bin/vite.js','build','--config','browser/vite.config.mjs'])
  const after=currentInputs()
  const changed=inputChanges(before,Object.fromEntries(Object.entries(after).filter(([key])=>key!=='bundleSha256')))
  if(changed.length)throw new Error('Inputs changed during compilation: '+changed.join(', '))
  Object.assign(inputs,after)
  inputs.bundleFiles=hashes(resolve(repo,'.work/plugin-browser/bundle'))
  inputs.sdkFiles=JSON.parse(readFileSync(resolve(repo,'.integration/host-provenance.json'))).uiSdk.files
  inputs.harnessFiles=hashes(resolve(repo,'ui/browser'))
  inputs.harnessSha256=aggregate(inputs.harnessFiles)
  const check=spawnSync('python3',['browser/check-jar-bundle.py'],{cwd:resolve(repo,'ui'),encoding:'utf8'})
  if(check.stdout)writeFileSync(resolve(output,'jar-bundle.json'),check.stdout)
  if(check.status!==0)throw new Error('Production jar differs from tested bundle: '+check.stdout+check.stderr)
  inputs.jar=JSON.parse(check.stdout)
  const browser=await chromium.launch();inputs.chromium=browser.version();await browser.close()
  write('inputs.json',inputs)
  const result=spawnSync(process.execPath,['node_modules/@playwright/test/cli.js','test','--config','browser/playwright.config.mjs',...process.argv.slice(2)],{cwd:resolve(repo,'ui'),stdio:'inherit',env:process.env})
  let report
  try{report=JSON.parse(readFileSync(resolve(output,'report.json')))}catch{throw new Error('Browser runner did not produce a report: '+(result.error || result.status))}
  process.exitCode=result.status===0 && report.gatePassed ? 0 : 1
} catch(error){setupFailure(error);console.error(error);process.exitCode=1}
