// Runs before npm ci / browser installation so setup failure still leaves explicit notRun evidence.
import {mkdirSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {repo,currentInputs} from './provenance.mjs'
import {manifest} from './matrix.mjs'
const output=resolve(repo,'.evidence/ui-browser');mkdirSync(output,{recursive:true})
let inputs={image:'',hostExecutableSha256:'',sourceCommit:'',sourceSha256:'',lockSha256:'',bundleSha256:'',sdkSha256:''}
const errors=['Browser gate setup has not completed; see CI step log']
try{Object.assign(inputs,currentInputs({bundle:false}))}catch(error){errors.push(String(error))}
for(const [name,value] of Object.entries({'manifest.json':manifest,'inputs.json':inputs,'report.json':{
  schemaVersion:1,gate:manifest.gate,gatePassed:false,inputs,finalInputs:inputs,errors,
  summary:{planned:manifest.cases.length,executed:0,passed:0,failed:0,notRun:manifest.cases.length,skipped:0},
  cases:manifest.cases.map(row=>({...row,status:'notRun',durationMs:0,errors:[],attachments:[]}))
}}))writeFileSync(resolve(output,name),JSON.stringify(value,null,2)+'\n')
