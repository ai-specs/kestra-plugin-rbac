import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real Vue SFCs and published design-system/Element Plus DOM. No network or browser.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','localStorage','sessionStorage','Storage','StorageEvent','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
  Object.defineProperty(globalThis,name,{value:typeof window[name]==='function'&&['getComputedStyle','requestAnimationFrame','cancelAnimationFrame'].includes(name)?window[name].bind(window):window[name],configurable:true,writable:true})
// Happy DOM eagerly creates finished promises even when the consumer never reads them.
// Observe only cancellation of this document's real virtual animations; no global error suppression.
const cancelledAnimations=[]
const animate=window.Element.prototype.animate
window.Element.prototype.animate=function(...args) {
  const animation=animate.apply(this,args)
  animation.finished.catch(error=>{if(error.name!=='AbortError')throw error;cancelledAnimations.push({element:this.tagName,name:error.name})})
  return animation
}
const hooks=registerHooks({load(url,context,next){
  if(/\.(?:png|svg|woff2?|ttf)$/.test(url))return {format:'module',source:'export default '+JSON.stringify(url),shortCircuit:true}
  if(url.endsWith('.css'))return {format:'module',source:'export default {}',shortCircuit:true}
  if(url.endsWith('.vue')) {
    const filename=fileURLToPath(url),{descriptor}=parse(readFileSync(filename,'utf8'),{filename})
    const script=compileScript(descriptor,{id:filename,inlineTemplate:Boolean(descriptor.scriptSetup),...(!descriptor.scriptSetup?{genDefaultAs:'__component'}:{})})
    const source=descriptor.scriptSetup?script.content:script.content+'\n'+compileTemplate({source:descriptor.template.content,filename,id:filename}).code+'\n__component.render=render;export default __component;'
    return {format:'module',source,shortCircuit:true}
  }
  return next(url,context)
}})
const Vue=await import('vue')
const {createI18n}=await import('vue-i18n')
const {ElConfigProvider}=await import('element-plus')
const {createMemoryHistory,createRouter}=await import('vue-router')
const DS=await import('@kestra-io/design-system')
const {default:ApplicationGroupCards}=await import('../src/ApplicationGroupCards.vue')
const {default:AccessList}=await import('../src/AccessList.vue')
const {nativeChips,matchesChips,readListQuery}=await import('../src/access-control-model.js')
const messages=Object.assign({},...['en','fr','de'].map(locale=>JSON.parse(readFileSync(new URL('../src/translations/'+locale+'.json',import.meta.url),'utf8'))))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,20));await Vue.nextTick()}
const click=node=>{assert(node,'native control missing');assert(!node.disabled,'native control disabled');node.click()}
const shown=node=>{for(let current=node;current;current=current.parentElement)if(current.style.display==='none'||current.getAttribute('aria-hidden')==='true')return false;return true}

const {default:App}=await import('../src/App.vue')
const {createNavigationHooks}=await import('../src/navigation-lifecycle.js')
const response=(body,status=200)=>({ok:status<400,status,json:async()=>structuredClone(body)})
const wait=async(predicate,message)=>{for(let i=0;i<80;i++){if(predicate())return;await tick()}assert(predicate(),message)}
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
async function mountApp(requestOverride){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
 const hooks=createNavigationHooks(),requests=[],navigations=[]
 const state=Vue.shallowReactive({context:null})
 let capability={allowed:true,identityRead:true,identityManagement:true,userDirectory:true,subject:'alice',issuer:'issuer-a'},groupResponse=null,navigationWait=null
 const items=[{id:'kestra-a',displayName:'First',active:true,counts:{scopes:0,applications:0,subjects:0}},{id:'kestra-b',displayName:'Second',active:true,counts:{scopes:0,applications:0,subjects:0}}]
 const context={apiBase:'/api/v1/main',path:'application-groups',fullPath:'application-groups',tenant:'main',signal:new AbortController().signal,
  navigate:async(fullPath,options)=>{navigations.push(fullPath);assert(await hooks.beforeNavigate({extensionPath:fullPath.split('?')[0],fullPath,query:router.resolve('/'+fullPath).query}),'host beforeNavigate rejected query');if(navigationWait)await navigationWait(fullPath);state.context={...state.context,path:fullPath.split('?')[0],fullPath}},
  request:async(path,options={})=>{requests.push(path);const overridden=await requestOverride?.(path,options);if(overridden)return overridden;if(path.includes('/capabilities'))return response(capability);if(path.includes('/application-groups?'))return groupResponse?groupResponse():response({items,total:items.length,page:1,size:100});if(path.includes('/users?'))return response({items:[{subject:'alice',active:true}],total:1,page:1,size:100});throw new Error('Unexpected request '+path)}
 }
 state.context=context
 const container=document.createElement('div');document.body.append(container)
 const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(App,{context:state.context}))})
 for(const [name,component] of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
 const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n);app.use(i18n);app.use(router);app.provide('rbac-portals',document.body);app.provide('rbac-navigation-hooks',hooks);app.mount(container)
 await wait(()=>container.querySelector('.customize-button'),'App not loaded')
 return {container,router,hooks,state,requests,navigations,items,setNavigationWait:f=>{navigationWait=f},setCapability:c=>{capability=c},setGroupResponse:f=>{groupResponse=f},capability:()=>capability,close(){app.unmount();container.remove()}}
}
async function openAppAdvanced(view){click(view.container.querySelector('.customize-button'));await tick();const starter=document.querySelector('.advanced-add');if(starter)click(starter);await tick();return document.querySelector('.adv-builder')}
const inputText=(input,value)=>{assert(input,'native text input absent');input.focus();input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}))}
test('App native query echo preserves advanced instance, focus and multiple conditions through full authorized reload',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);assert(dialog);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kestra');await wait(()=>v.navigations.length>0,'native query did not reach host');await tick()
  assert(document.querySelector('.adv-builder')===dialog,'host context query echo must not unmount native filter')
  assert(document.activeElement===input,'query echo must preserve input focus');assert(v.requests.filter(p=>p.includes('/capabilities')).length>=2,'query must recheck capabilities')
  click(dialog.querySelector('.adv-footer .adv-add'));await tick();assert.equal(dialog.querySelectorAll('.adv-row').length,2)
  const field=dialog.querySelectorAll('.adv-row')[1].querySelector('.cond-field');click(field.querySelector('.kel-select__wrapper'));await tick();const option=[...document.querySelectorAll('[role="option"]')].find(n=>n.textContent.trim()==='Application group ID');assert(option);click(option);await tick()
  const inputs=dialog.querySelectorAll('.cond-value-input input');inputText(inputs[1],'kestra-a');await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','query refresh incomplete')
  assert(document.querySelector('.adv-builder')===dialog);assert.equal(dialog.querySelectorAll('.adv-row').length,2)
 }finally{v.close()}
})
test('App consecutive native queries supersede slow reads and host echo keeps latest URL and data',async()=>{
 const v=await mountApp();const old=deferred(),second=deferred();let calls=0
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setGroupResponse(()=>++calls===1?old.promise:calls===2?second.promise:response({items:v.items,total:2,page:1,size:100}))
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kes');await wait(()=>calls===1,'slow query did not start');inputText(input,'kestra-a');await wait(()=>calls>=2,'second native input blocked by query refresh')
  old.resolve(response({items:[{...v.items[0],id:'stale-secret'}],total:1,page:1,size:100}));await tick()
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true','newer request must still be pending')
  inputText(input,'kestra-b');await wait(()=>calls===3,'stale finally blocked third native input during pending second read')
  await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','latest query incomplete')
  second.resolve(response({items:[{...v.items[0],id:'second-stale'}],total:1,page:1,size:100}));await tick()
  assert(document.querySelector('.adv-builder')===dialog);assert(v.state.context.fullPath.includes('kestra-b'));assert(!v.container.textContent.includes('stale-secret'));assert(!v.container.textContent.includes('second-stale'))
 }finally{old.resolve(response({items:[],total:0,page:1,size:100}));second.resolve(response({items:[],total:0,page:1,size:100}));v.close()}
})

for(const mode of ['identity','permission','403','503'])test('App query authorization '+mode+' clears old filter and sensitive rows; dependencies remain retryable',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  if(mode==='identity')v.setCapability({...v.capability(),subject:'bob'})
  if(mode==='permission')v.setCapability({...v.capability(),identityManagement:false})
  if(mode==='403'||mode==='503')v.setGroupResponse(()=>response({code:mode==='403'?'access_denied':'authorization_unavailable'},Number(mode)))
  inputText(dialog.querySelector('.cond-value-input input'),'kestra');await wait(()=>v.navigations.length>0,'query echo absent');await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','reload unfinished')
  assert(!document.querySelector('.adv-builder'),'changed authorization or failed read must unmount old popup')
  if(mode==='403'||mode==='503'){
   assert(v.container.querySelector('[data-test="request-error"]'));assert(!v.container.querySelector('[data-test="group-card"]'),'old authorized rows must be cleared')
   if(mode==='503'){v.setGroupResponse(null);click(v.container.querySelector('[data-test="request-error"] button'));await wait(()=>v.container.querySelector('.customize-button'),'dependency retry did not reload')}
  }else assert(v.container.querySelector('.customize-button'),'allowed fresh identity/capabilities must complete full remount')
 }finally{v.close()}
})
test('App host query history, explicit refresh, tenant/path changes and malformed query keep authorization reloads',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick();inputText(dialog.querySelector('.cond-value-input input'),'kestra-a');await wait(()=>v.navigations.length>0,'echo missing');await tick()
  const filtered=v.state.context.fullPath
  v.state.context={...v.state.context,fullPath:'application-groups'};await tick();await wait(()=>!Object.keys(v.router.currentRoute.value.query).length,'host history clear missing');assert(document.querySelector('.adv-builder')===dialog)
  v.state.context={...v.state.context,fullPath:filtered};await tick();await wait(()=>v.router.currentRoute.value.fullPath.includes('kestra-a'),'host forward query missing');assert(document.querySelector('.adv-builder')===dialog)
  click(dialog.querySelector('.adv-header-close'));await tick();const trigger=v.container.querySelector('.customize-button')
  const refreshes=v.container.querySelectorAll('.refresh-button');assert.equal(refreshes.length,1,'native refresh control absent/ambiguous');const refresh=refreshes[0]
  const capabilitiesBefore=v.requests.filter(p=>p.includes('/capabilities')).length
  click(refresh);await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','explicit refresh incomplete')
  assert(v.requests.filter(p=>p.includes('/capabilities')).length>capabilitiesBefore,'refresh must recheck capabilities')
  assert(v.container.querySelector('.customize-button')!==trigger,'explicit refresh must remount the native filter')
  const afterRefresh=v.container.querySelector('.customize-button')
  // Host full path reload through a tenant change must clear the previous instance.
  v.state.context={...v.state.context,tenant:'other'};await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','tenant reload missing');assert(v.container.querySelector('.customize-button')!==afterRefresh)
  const readsBeforeInvalid=v.requests.length
  v.state.context={...v.state.context,path:'users',fullPath:'users?page=-1'};await tick();await wait(()=>v.container.querySelector('[data-test="request-error"] button'),'malformed query must show Clear');assert(!v.container.querySelector('.customize-button'));assert.equal(v.requests.length,readsBeforeInvalid,'invalid query must fail before capabilities/users/subject reads')
  click(v.container.querySelector('[data-test="request-error"] button'));await wait(()=>v.container.querySelector('.customize-button'),'invalid query clear did not reload')
  assert.equal(v.state.context.fullPath,'users');assert(v.requests.some(p=>p.includes('/users?')))
 }finally{v.close()}
})
test('App host acknowledgement ordering coalesces newer native query without reverting visible input or formal URL',async()=>{
 const v=await mountApp(),firstAck=deferred();let calls=0
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setNavigationWait(()=>++calls===1?firstAck.promise:Promise.resolve())
  const input=dialog.querySelector('.cond-value-input input');inputText(input,'kes');await wait(()=>v.navigations.length===1,'first query not sent')
  inputText(input,'kestra-b');await tick();assert(v.router.currentRoute.value.fullPath.includes('kestra-b'),'second input must update native router while host acknowledgement pending')
  firstAck.resolve();await wait(()=>v.state.context.fullPath.includes('kestra-b'),'formal host URL must settle to latest native query');await tick()
  assert(v.router.currentRoute.value.fullPath.includes('kestra-b'));assert(document.querySelector('.adv-builder')===dialog);assert.equal(input.value,'kestra-b')
 }finally{firstAck.resolve();v.close()}
})
for(const change of ['path','tenant','dispose'])test('App pending query acknowledgement cannot restore obsolete navigation after '+change,async()=>{
 const v=await mountApp(),ack=deferred()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick();v.setNavigationWait(()=>ack.promise)
  inputText(dialog.querySelector('.cond-value-input input'),'kes');await wait(()=>v.navigations.length===1,'pending ack not reached')
  inputText(dialog.querySelector('.cond-value-input input'),'kestra-b');await tick()
  if(change==='dispose'){v.close();ack.resolve();await tick();assert.equal(v.navigations.length,1);return}
  v.state.context={...v.state.context,...(change==='path'?{path:'users',fullPath:'users'}:{tenant:'other'})}
  await tick();ack.resolve();await tick();await tick()
  assert.equal(v.state.context.fullPath,change==='path'?'users':'application-groups','old query ack must not restore stale destination')
  if(change==='tenant')assert.equal(v.state.context.tenant,'other')
  assert(!v.navigations.slice(1).some(p=>p.includes('kestra-b')),'obsolete queued query was sent')
 }finally{ack.resolve();if(change!=='dispose')v.close()}
})
test('App rejected host query navigation releases acknowledgement and same-target real history reloads',async()=>{
 const v=await mountApp()
 try{
  const dialog=await openAppAdvanced(v);click(dialog.querySelector('.adv-footer .adv-add'));await tick()
  v.setNavigationWait(()=>Promise.reject(Object.assign(new Error('navigation unavailable'),{status:503})))
  inputText(dialog.querySelector('.cond-value-input input'),'kestra-a');await wait(()=>v.container.querySelector('[data-test="request-error"]'),'navigate rejection must remain visible')
  const rejected=v.navigations[0];v.setNavigationWait(null)
  v.state.context={...v.state.context,fullPath:rejected};await tick();await wait(()=>v.router.currentRoute.value.fullPath.includes('kestra-a'),'real same-target host history was ignored after rejection')
  assert(!v.container.querySelector('[data-test="request-error"]'))
 }finally{v.close()}
})
// Exercise actual App reads, native permission rows, router canonicalization and host echo.
for(const mode of ['revoked','partial','missing','disabled','invalid'])test('App subject deep link revalidates '+mode+' without broadening rows',async()=>{
 let granted=['scope-a','scope-b'],active=true,missing=false
 const permissions=[{key:'permit-a',name:'Permit A',scopeKeys:['scope-a'],details:'A'},{key:'permit-b',name:'Permit B',scopeKeys:['scope-b'],details:'B'},{key:'outside',name:'Outside',scopeKeys:['scope-other'],details:'Other'}]
 const page=items=>response({items:items.map(item=>({...item,parentRevision:1})),total:items.length,page:1,size:100,directoryRevision:1,parentRevision:1})
 const v=await mountApp(path=>{
  if(path.includes('/scope-grants/detail?'))return missing?response({code:'not_found'},404):response({subject:'alice',scopeKeys:granted})
  if(path.includes('/users/detail?'))return response({subject:'alice',active})
  if(path.endsWith('/credentials'))return response({})
  if(path.includes('/permissions?'))return page(permissions)
  if(path.endsWith('/applications/~Y2xpZW50'))return response({clientId:'client',implementationKind:'external',revision:1})
  if(path.includes('/applications?'))return page([{clientId:'client',active:true,supportedScopeKeys:['scope-a','scope-b']}])
  if(path.includes('/scopes?'))return page(['scope-a','scope-b','scope-other'].map(key=>({key,name:key})))
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',issuer:'issuer-a',revision:1})
 })
 const base='application-groups/kestra-a/applications/~Y2xpZW50'
 const original=base+'?filterSubject=alice&scopeKeys=scope-a&scopeKeys=scope-b'
 const enter=fullPath=>{v.state.context={...v.state.context,path:base,fullPath}}
 const settled=async()=>{await tick();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','subject read unfinished');await tick()}
 const rows=()=>[...v.container.querySelectorAll('[data-test="permission-row"]')].map(n=>n.dataset.permissionKey)
 try{
  enter(original);await settled();assert.deepEqual(rows(),['permit-a','permit-b'])
  granted=mode==='partial'?['scope-b']:[];active=mode!=='disabled';missing=mode==='missing'
  const before=v.requests.length
  if(mode==='invalid')enter(base+'?filterSubject=alice&filterSubject=bob&scopeKeys=scope-a')
  else {const refresh=v.container.querySelector('[data-test="application-permissions"] .refresh-button')||[...v.container.querySelectorAll('[data-test="application-permissions"] button')].find(n=>/refresh/i.test(n.getAttribute('aria-label')||n.textContent));click(refresh)}
  await settled()
  if(mode==='invalid'){assert(v.container.querySelector('[data-test="request-error"]'));assert.equal(v.requests.length,before);assert.deepEqual(rows(),[]);return}
  assert(v.requests.slice(before).some(p=>p.includes('/scope-grants/detail?subject=alice')),'refresh must reread grant')
  if(mode==='missing'||mode==='disabled'){assert(v.container.querySelector('[data-test="request-error"]'));assert.deepEqual(rows(),[]);return}
  assert(v.requests.slice(before).some(p=>p.includes('/users/detail?subject=alice')),'refresh must reread account')
  const expected=mode==='partial'?['scope-b']:[]
  assert.deepEqual(v.router.currentRoute.value.query.scopeKeys===undefined?[]:[v.router.currentRoute.value.query.scopeKeys].flat(),expected,'expired scopeKeys must leave canonical router query')
  assert.equal(v.router.currentRoute.value.query.filterSubject,'alice')
  await wait(()=>v.state.context.fullPath===v.router.currentRoute.value.fullPath.slice(1),'canonical URL must reach host echo')
  assert.deepEqual(rows(),mode==='partial'?['permit-b']:[])
  // Fresh context and stale host history must both revalidate rather than restore old grants.
  const canonical=v.state.context.fullPath
  for(const destination of [base+'?filterSubject=alice',original,canonical]){enter(destination);await settled();assert.deepEqual(rows(),destination===base+'?filterSubject=alice'?[]:mode==='partial'?['permit-b']:[]);assert.equal(v.router.currentRoute.value.query.filterSubject,'alice');assert(!v.state.context.fullPath.includes('scope-a'))}
 }finally{v.close()}
})
for(const kind of ['group','scope','application'])test('App native box tab cancel and same-target retry preserves '+kind+' draft and controlled active',async()=>{
 const page=items=>response({items,total:items.length,page:1,size:100,directoryRevision:1})
 const v=await mountApp(path=>{
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',displayName:'First',issuer:'issuer-a',active:true,revision:1})
  if(path.includes('/scopes?')||path.includes('/applications?')||path.includes('/scope-grants?'))return page([])
 })
 const base='application-groups/kestra-a',source=kind==='group'?'overview':kind==='scope'?'scopes':'applications',target=source==='overview'?'scopes':'overview'
 const initial=base+(source==='overview'?'':'/'+source)
 const visibleDialog=()=>[...document.querySelectorAll('[data-test="discard-confirm"]')].find(shown)
 const tab=name=>v.container.querySelector('[role="tab"][id="tab-'+name+'"]')
 try{
  v.state.context={...v.state.context,path:initial,fullPath:initial};await wait(()=>v.container.querySelector(kind==='group'?'[data-test="group-edit"]':kind==='scope'?'[data-test="scope-add"]':'[data-test="application-register"]'),'inline entry absent')
  click(v.container.querySelector(kind==='group'?'[data-test="group-edit"]':kind==='scope'?'[data-test="scope-add"]':'[data-test="application-register"]'));await tick()
  const form=v.container.querySelector('.rbac-inline-form'),input=form.querySelector('input');inputText(input,'changed-draft');await tick()
  const destination=tab(target);assert(destination);destination.focus();click(destination)
  await wait(()=>visibleDialog(),'first tab click must confirm discard')
  click([...visibleDialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Cancel'));await tick()
  assert(!visibleDialog(),'cancel must close visible dialog');assert.equal(input.value,'changed-draft');assert.equal(v.state.context.fullPath,initial)
  assert.equal(document.activeElement,destination,'cancel must restore trigger focus')
  click(destination);await wait(()=>visibleDialog(),'same target second click must reopen visible discard dialog')
  assert.equal(tab(source).getAttribute('aria-selected'),'true','cancelled navigation must retain controlled active tab')
  assert.equal(tab(target).getAttribute('aria-selected'),'false')
  click([...visibleDialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Discard changes'))
  const final=base+(target==='overview'?'':'/'+target)
  await wait(()=>v.state.context.fullPath===final&&v.container.querySelector('main').getAttribute('aria-busy')==='false','confirmed tab must reach authorized host destination')
  await tick();assert.equal(tab(target).getAttribute('aria-selected'),'true');assert(!v.container.querySelector('.rbac-inline-form'));assert(!visibleDialog());assert.equal(document.activeElement,tab(target),'confirmed navigation must restore tab trigger focus')
  // Normal native tab change must still synchronize the host URL after discard.
  click(tab(source));await wait(()=>v.state.context.fullPath===initial,'normal tab must navigate');await tick();assert.equal(tab(source).getAttribute('aria-selected'),'true')
 }finally{v.close()}
})
test('App native box tabs retain active and host URL during real editor save and one-time credential',async()=>{
 const receipt=deferred();let saving=false
 const v=await mountApp((path,options)=>{
  if(path.endsWith('/users')&&options.method==='POST'){saving=true;return receipt.promise}
 })
 const dialog=()=>[...document.querySelectorAll('[data-test="temporary-password-once"]')].find(shown)
 const tab=name=>v.container.querySelector('[role="tab"][id="tab-'+name+'"]')
 try{
  click(tab('users'));await wait(()=>v.state.context.fullPath==='users'&&v.container.querySelector('main').getAttribute('aria-busy')==='false','users tab not loaded')
  click([...v.container.querySelectorAll('button')].find(n=>/Create/.test(n.textContent)));await wait(()=>document.querySelector('[data-test="drawer-user"] input[name="username"]'),'user drawer absent')
  inputText(document.querySelector('[data-test="drawer-user"] input[name="username"]'),'tab-lock-account')
  document.querySelector('[data-test="drawer-user"] form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>saving,'real editor POST not started')
  click(tab('application-groups'));await tick();assert.equal(v.state.context.fullPath,'users');assert.equal(tab('users').getAttribute('aria-selected'),'true');assert.equal(await v.hooks.beforeNavigate({fullPath:'application-groups'}),false,'saving must block external host navigation')
  receipt.resolve(response({subject:'tab-lock-account',revision:1,temporaryPassword:'isolated-component-test-value'}));await wait(()=>dialog()&&v.container.querySelector('main').getAttribute('aria-busy')==='false','one-time dialog absent')
  click(tab('application-groups'));await tick();assert.equal(v.state.context.fullPath,'users');assert.equal(tab('users').getAttribute('aria-selected'),'true');assert(dialog());assert.equal(await v.hooks.beforeNavigate({fullPath:'application-groups'}),false,'oneTime must block external host navigation')
  click([...dialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Close'));await tick()
  click(tab('application-groups'));await wait(()=>v.state.context.fullPath==='application-groups','closing oneTime must restore normal tab navigation');await tick();assert.equal(tab('application-groups').getAttribute('aria-selected'),'true')
 }finally{receipt.resolve(response({subject:'tab-lock-account',revision:1,temporaryPassword:'isolated-component-test-value'}));v.close()}
})
test('App slow subject query retains real credential-write and oneTime navigation locks',async()=>{
 const grant=deferred(),rotation=deferred();let slow=false,grantStarted=false,rotationStarted=false,credentialRevision=1
 const page=items=>response({items,total:items.length,page:1,size:100,directoryRevision:1,parentRevision:1})
 const v=await mountApp((path,options)=>{
  if(path.endsWith('/credentials/rotate')){rotationStarted=true;return rotation.promise}
  if(path.endsWith('/credentials'))return response({clientId:'client',clientType:'confidential',secretSet:true,maskedHint:'test-only',credentialRevision,rotationMode:'online'})
  if(path.includes('/scope-grants/detail?')){if(slow){grantStarted=true;return grant.promise}return response({subject:'alice',scopeKeys:['scope-a']})}
  if(path.includes('/users/detail?'))return response({subject:'alice',active:true})
  if(path.includes('/permissions?'))return page([{key:'permit-a',name:'Permit A',scopeKeys:['scope-a'],details:'A',parentRevision:1}])
  if(path.endsWith('/applications/~Y2xpZW50'))return response({clientId:'client',implementationKind:'external',revision:1})
  if(path.includes('/applications?'))return page([{clientId:'client',active:true,supportedScopeKeys:['scope-a']}])
  if(path.includes('/scopes?'))return page([{key:'scope-a',name:'A'}])
  if(path.endsWith('/application-groups/kestra-a'))return response({id:'kestra-a',issuer:'issuer-a',revision:1})
 })
 const base='application-groups/kestra-a/applications/~Y2xpZW50',initial=base+'?filterSubject=alice&scopeKeys=scope-a'
 const dialog=()=>[...document.querySelectorAll('[data-test="credential-once"]')].find(shown)
 const overview=()=>v.container.querySelector('[role="tab"][id="tab-overview"]')
 try{
  v.state.context={...v.state.context,path:base,fullPath:initial};await wait(()=>v.container.querySelector('[data-test="credential-rotate"]')&&v.container.querySelector('[data-test="permission-row"]'),'subject application absent')
  slow=true;v.state.context={...v.state.context,fullPath:initial+'&page=2'};await wait(()=>grantStarted,'slow same-path subject read absent')
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true')
  // Public credential panel remains mounted during the same-path read; no private state injection.
  click(v.container.querySelector('[data-test="credential-rotate"]'));await wait(()=>rotationStarted,'real rotation POST absent')
  click(overview());await tick();assert.equal(v.state.context.path,base);assert.equal(await v.hooks.beforeNavigate({fullPath:'users'}),false,'credential write must block external navigation during query read')
  credentialRevision=2;rotation.resolve(response({clientId:'client',credentialRevision:2,clientSecret:'isolated-component-test-secret'}));await wait(()=>dialog(),'real rotation oneTime absent')
  assert.equal(v.container.querySelector('main').getAttribute('aria-busy'),'true','subject read must remain pending')
  click(overview());await tick();assert.equal(v.state.context.path,base);assert.equal(v.container.querySelector('[role="tab"][id="tab-applications"]').getAttribute('aria-selected'),'true');assert.equal(await v.hooks.beforeNavigate({fullPath:'users'}),false,'oneTime must block external navigation during query read')
  click([...dialog().querySelectorAll('button')].find(n=>n.textContent.trim()==='Close'));await tick()
  grant.resolve(response({subject:'alice',scopeKeys:[]}));await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false'&&!v.state.context.fullPath.includes('scopeKeys'),'released subject read must normalize host URL')
  assert.equal(v.router.currentRoute.value.query.filterSubject,'alice');assert.equal(v.container.querySelectorAll('[data-test="permission-row"]').length,0)
 }finally{grant.resolve(response({subject:'alice',scopeKeys:[]}));rotation.resolve(response({clientId:'client',credentialRevision:2,clientSecret:'isolated-component-test-secret'}));v.close()}
})
test.after(()=>{hooks.deregister();window.happyDOM.abort()})
