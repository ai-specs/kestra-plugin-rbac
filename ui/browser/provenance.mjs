import {createHash} from 'node:crypto'
import {readFileSync,readdirSync} from 'node:fs'
import {resolve,relative} from 'node:path'
import {execFileSync} from 'node:child_process'
export const repo=resolve(import.meta.dirname,'../..')
export const sha=value=>createHash('sha256').update(value).digest('hex')
export const hashFile=file=>sha(readFileSync(file))
export const files=dir=>readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0).flatMap(entry=>entry.isDirectory()?files(resolve(dir,entry.name)):[resolve(dir,entry.name)])
export const hashes=dir=>Object.fromEntries(files(dir).map(file=>[relative(dir,file),hashFile(file)]))
export const aggregate=values=>sha(Object.entries(values).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([path,hash])=>path+'\0'+hash).join('\n'))
export function currentInputs({bundle=true}={}){
  const host=JSON.parse(readFileSync(resolve(repo,'.integration/host-provenance.json')))
  const sdk=Object.fromEntries(Object.keys(host.uiSdk.files).sort().map(file=>[file,hashFile(resolve(repo,'.integration/ui-sdk',file))]))
  if(JSON.stringify(sdk)!==JSON.stringify(Object.fromEntries(Object.entries(host.uiSdk.files).sort(([a],[b])=>a<b?-1:1))))throw new Error('SDK differs from published executable provenance')
  if(hashFile(resolve(repo,'.integration/host-webserver.jar'))!==host.sha256)throw new Error('Host executable differs from published image provenance')
  const image=readFileSync(resolve(repo,'host-contract.properties'),'utf8').trim().split('=')[1]
  if(image!==host.image)throw new Error('Host image pin differs from provenance')
  return {image,hostExecutableSha256:host.sha256,sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:repo,encoding:'utf8'}).trim(),
    sourceSha256:aggregate(hashes(resolve(repo,'ui/src'))),lockSha256:hashFile(resolve(repo,'ui/package-lock.json')),
    ...(bundle?{bundleSha256:aggregate(Object.fromEntries(Object.entries(hashes(resolve(repo,'.work/plugin-browser/bundle'))).filter(([path])=>path!=='manifest.json')))}:{}),sdkSha256:aggregate(sdk)}
}
export function inputChanges(before,after){return Object.keys(after).filter(key=>before[key]!==after[key])}
