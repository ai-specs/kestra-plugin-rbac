import {readFileSync,writeFileSync,mkdirSync} from 'node:fs'
import {resolve,relative} from 'node:path'
import {spawnSync} from 'node:child_process'
import {manifest} from './matrix.mjs'
import {repo,currentInputs,inputChanges,hashes,aggregate} from './provenance.mjs'
const output=resolve(repo,'.evidence/ui-browser')
export default class Reporter {
  constructor(){
    this.errors=[];this.startedAt=new Date().toISOString()
    this.rows=new Map(manifest.cases.map(row=>[row.id,{...row,status:'notRun',durationMs:0,errors:[],attachments:[]}]))
  }
  onBegin(config,suite){
    const ids=suite.allTests().map(test=>test.title)
    if(new Set(ids).size!==ids.length)this.errors.push('Duplicate browser case IDs')
    for(const id of ids)if(!this.rows.has(id))this.errors.push('Unknown browser case ID: '+id)
  }
  onError(error){this.errors.push(error.stack || error.message || String(error))}
  onTestEnd(test,result){
    const row=this.rows.get(test.title)
    if(!row)return
    const directory=resolve(output,'attachments',test.title.replaceAll('/','_'))
    const attachments=result.attachments.map(({name,contentType,path,body},index)=>{
      if(body){mkdirSync(directory,{recursive:true});path=resolve(directory,index+'-'+name+(contentType==='image/png'?'.png':contentType==='application/json'?'.json':'.txt'));writeFileSync(path,body)}
      return {name,contentType,...(path?{path:relative(repo,path)}:{})}
    })
    Object.assign(row,{status:result.status==='passed'?'passed':result.status==='skipped'?'skipped':'failed',durationMs:result.duration,
      errors:result.errors.map(error=>error.stack || error.message),attachments})
  }
  async onEnd(result){
    const cases=[...this.rows.values()],count=status=>cases.filter(row=>row.status===status).length
    const summary={planned:cases.length,executed:count('passed')+count('failed'),passed:count('passed'),failed:count('failed'),notRun:count('notRun'),skipped:count('skipped')}
    const inputs=JSON.parse(readFileSync(resolve(output,'inputs.json'),'utf8'))
    let finalInputs
    try{
      finalInputs=currentInputs()
      const changed=inputChanges(inputs,finalInputs)
      if(changed.length)this.errors.push('Inputs changed during browser execution: '+changed.join(', '))
      if(aggregate(hashes(resolve(repo,'ui/browser')))!==inputs.harnessSha256)this.errors.push('Browser harness changed during execution')
      const check=spawnSync('python3',['browser/check-jar-bundle.py'],{cwd:resolve(repo,'ui'),encoding:'utf8'})
      if(check.status!==0)this.errors.push('Final jar/bundle verification failed: '+check.stdout+check.stderr)
      else if(JSON.parse(check.stdout).jarSha256!==inputs.jar.jarSha256)this.errors.push('Jar changed during browser execution')
    }catch(error){this.errors.push(String(error))}
    const report={schemaVersion:1,gate:manifest.gate,startedAt:this.startedAt,finishedAt:new Date().toISOString(),
      gatePassed:this.errors.length===0&&result.status==='passed'&&summary.passed===summary.planned,
      inputs,finalInputs,errors:this.errors,summary,cases}
    mkdirSync(output,{recursive:true});writeFileSync(resolve(output,'report.json'),JSON.stringify(report,null,2)+'\n')
    writeFileSync(resolve(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n')
    if(this.errors.length)return {status:'failed'}
  }
}
