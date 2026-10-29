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

async function mountScenario(path,kind,{scopeKeys=['a','b'],rejectCanonical=false}={}){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
 const hooks=createNavigationHooks(),requests=[],navigations=[],state=Vue.shallowReactive({context:null})
 const hostRouter=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
 await hostRouter.push('/'+path)
 hostRouter.beforeEach(to=>hooks.beforeNavigate({extensionPath:to.path.slice(1),fullPath:to.fullPath,query:to.query}))
 hostRouter.afterEach((to,from,failure)=>{if(!failure&&state.context)state.context={...state.context,path:to.path.slice(1),fullPath:to.fullPath.slice(1)}})
 const scopes=scopeKeys.map(key=>({key,name:key.toUpperCase(),description:'',revision:1}))
 const users=Array.from({length:61},(_,i)=>({subject:'user-'+String(i).padStart(3,'0'),name:'User',email:'',active:true,revision:1}))
 let deleteWait=null,readWait=null
 const permissions=Array.from({length:61},(_,i)=>({key:'permission-'+String(i).padStart(3,'0'),name:'Permission',scopeKeys:kind==='permissions'?['a']:[],details:'',implementationKind:'external-declaration',revision:1,parentRevision:1}))
 const context={apiBase:'/api/v1/main',path:path.split('?')[0],fullPath:path,tenant:'main',signal:new AbortController().signal,
 navigate:async(target,options)=>{if(rejectCanonical&&requests.some(r=>r.method==='DELETE'))throw Object.assign(new Error('canonical navigation unavailable'),{status:503});const failure=await hostRouter[options?.replace?'replace':'push']('/'+target);navigations.push({target,allowed:!failure,replace:options?.replace});if(failure)throw Object.assign(new Error('host navigation rejected'),{status:409})},
 request:async(url,options={})=>{requests.push({url,method:options.method??'GET'});
  if(url.includes('/capabilities'))return response({allowed:true,identityRead:true,identityManagement:true,userDirectory:true,subject:'manager',issuer:'issuer'})
  if(options.method==='DELETE'){if(deleteWait)await deleteWait;scopes.splice(scopes.findIndex(x=>x.key===url.split('/').at(-1)),1);return response(null,200)}
  if(url.includes('/permissions?')){if(readWait)await readWait;return response({items:permissions,total:61,page:1,size:100,parentRevision:1})}
  if(url.endsWith('/applications/~Y2xpZW50'))return response({clientId:'client',description:'',audiences:[],implementationKind:'external-declaration',revision:1,supportedScopeKeys:['a']})
  if(url.includes('/users?')){if(readWait)await readWait;return response({items:users,total:61,page:1,size:100})}
  if(url.endsWith('/application-groups/g'))return response({id:'g',issuer:'issuer',displayName:'Group',revision:1,authorizationVersion:1})
  const items=url.includes('/scopes?')?scopes:url.includes('/applications?')?[{clientId:'client',revision:1}]:[];return response({items,total:items.length,page:1,size:100,directoryRevision:1})
 }}
 state.context=context
 const container=document.createElement('div');document.body.append(container)
 const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(App,{context:state.context}))})
 for(const [name,component]of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
 const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n);app.use(router);app.use(i18n);app.provide('rbac-portals',document.body);app.provide('rbac-navigation-hooks',hooks);app.mount(container)
 await wait(()=>container.querySelector('main')?.getAttribute('aria-busy')==='false'&&container.querySelector(kind==='scope'?'[data-test="scope-detail"]':kind==='groups'?'.management-toolbar':'[data-test="list-total"]'),'scenario did not load')
 return {container,router,hostRouter,hooks,state,requests,navigations,setDeleteWait:p=>deleteWait=p,setReadWait:p=>readWait=p,close(){app.unmount();container.remove()}}
}
test('App successful scope deletion acknowledges canonical host navigation after write while pending write still rejects external leave',async()=>{
 const v=await mountScenario('application-groups/g/scopes/a','scope'),gate=deferred()
 try{
  v.setDeleteWait(gate.promise);click(v.container.querySelector('[data-test="scope-delete"]'));await tick()
  const confirm=document.querySelector('[data-test="delete-confirm"] .kel-button--danger');click(confirm);await tick()
  assert.equal(await v.hooks.beforeNavigate({extensionPath:'users',fullPath:'users',query:{}}),false,'pending DELETE must reject external navigation')
  gate.resolve();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','delete reload unfinished');await tick()
  assert.equal(v.state.context.fullPath,'application-groups/g/scopes/b','successful DELETE must acknowledge new canonical URL')
  assert.equal(v.router.currentRoute.value.path,'/application-groups/g/scopes/b');assert.equal(v.container.querySelector('[data-test="scope-item"][aria-current="true"] code')?.textContent,'b')
  assert.equal(v.requests.filter(x=>x.method==='DELETE').length,1)
 }finally{gate.resolve();v.close()}
})
for(const kind of ['users','permissions'])test('App actual ManagementTable ElPagination '+kind+' preserves page2 through host echo and slow full data reload',async()=>{
 const v=await mountScenario((kind==='users'?'users':'application-groups/g/applications/~Y2xpZW50')+'?page=1&size=25',kind),gate=deferred()
 try{
  const rows=()=>[...v.container.querySelectorAll(kind==='users'?'[data-test="user-row"]':'[data-test="permission-row"]')].map(n=>n.textContent)
  assert.equal(rows().length,25);assert.equal(rows()[0],kind==='users'?'user-000':'permission-000')
  v.setReadWait(gate.promise);click(v.container.querySelector('[data-test="list-total"] .btn-next'));await tick()
  await wait(()=>v.navigations.some(n=>n.target.includes('page=2')),'ElPagination failed to emit page2 host query')
  gate.resolve();await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','page2 reload unfinished');await tick();await tick()
  assert.equal(v.router.currentRoute.value.query.page,'2');assert(v.state.context.fullPath.includes('page=2'));assert.equal(rows()[0],kind==='users'?'user-025':'permission-025');assert.equal(rows().length,25);assert.equal(v.container.querySelector('[data-test="list-total"] [aria-current="true"]')?.textContent.trim(),'2','native active page must be 2 after host ack')
 }finally{gate.resolve();v.close()}
})
test('App cross-path host push and real router back preserve permission query without duplicate replace echo after settled scope load',async()=>{
 const initial='application-groups/g/applications/~Y2xpZW50?permissionKey=permission-000'
 const v=await mountScenario(initial,'permissions')
 try{
  const target=v.container.querySelector('[data-test="application-supported-scopes"] button');assert(target);click(target)
  await wait(()=>v.state.context.path==='application-groups/g/scopes/a'&&v.container.querySelector('[data-test="scope-item"][aria-current="true"]')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','scope navigation not settled')
  assert.equal(v.navigations.filter(n=>n.target==='application-groups/g/scopes/a').length,1,'host context echo must not replace successful cross-path push')
  assert.equal(v.navigations.at(-1).replace,false)
  v.hostRouter.back();await wait(()=>v.state.context.fullPath===initial&&v.container.querySelector('[data-test="permission-row"]')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','actual host-router back failed')
  assert.equal(v.container.querySelector('[data-test="permission-row"]').textContent,'permission-000')
 }finally{v.close()}
})
for(const mode of ['last','noncurrent','navigation-reject'])test('App successful scope delete '+mode+' preserves canonical selection or exposes post-reload navigation failure',async()=>{
 const v=await mountScenario('application-groups/g/scopes/a','scope',{scopeKeys:mode==='last'?['a']:['a','b','c'],rejectCanonical:mode==='navigation-reject'})
 try{
  const deleted=mode==='noncurrent'?'b':'a'
  const button=[...v.container.querySelectorAll('li')].find(li=>li.querySelector('code')?.textContent===deleted)?.querySelector('[data-test="scope-delete"]');click(button);await tick();click(document.querySelector('[data-test="delete-confirm"] .kel-button--danger'))
  await wait(()=>v.requests.some(r=>r.method==='DELETE')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','delete not completed');await tick()
  if(mode==='navigation-reject'){assert(v.container.querySelector('[data-test="request-error"]'),'post-reload navigation rejection was swallowed by stale mutation generation');assert.equal(v.state.context.path,'application-groups/g/scopes/a')}
  else {assert.equal(v.state.context.path,mode==='last'?'application-groups/g/scopes':'application-groups/g/scopes/a');assert.equal(v.container.querySelector('[data-test="scope-item"][aria-current="true"] code')?.textContent,mode==='last'?undefined:'a')}
  assert.equal(v.requests.filter(r=>r.method==='DELETE').length,1)
 }finally{v.close()}
})
for(const kind of ['users','permissions'])test('App native page-size 100 is atomic with page1, preserves filters and exact authorized rows: '+kind,async()=>{
 const path=kind==='users'?'users':'application-groups/g/applications/~Y2xpZW50'
 const filters=kind==='users'?'filters[subject][EQUALS]=user-030':'scopeKeys=a&filters[name][CONTAINS]=Permission'
 const v=await mountScenario(path+'?page=2&size=25'+(kind==='permissions'?'&'+filters:''),kind)
 try{
  const beforeQuery={...v.router.currentRoute.value.query}
  click(v.container.querySelector('[data-test="list-total"] .kel-select__wrapper'));await tick()
  const options=[...document.querySelectorAll('[role="option"]')].filter(n=>n.textContent.trim()==='100/page');assert.equal(options.length,1,'native 100 option missing/ambiguous');click(options[0]);await tick()
  await wait(()=>v.container.querySelector('main').getAttribute('aria-busy')==='false','page-size refresh incomplete');await tick()
  assert.equal(v.router.currentRoute.value.query.size,'100','second same-tick page1 event must not overwrite size100')
  assert.equal(v.router.currentRoute.value.query.page,undefined);assert(v.state.context.fullPath.includes('size=100'))
  for(const [key,value]of Object.entries(beforeQuery))if(!['page','size'].includes(key))assert.deepEqual([v.router.currentRoute.value.query[key]].flat(),[value].flat(),'page-size change erased filter '+key)
  const row=kind==='users'?'user-row':'permission-row',ids=[...v.container.querySelectorAll('[data-test="'+row+'"]')].map(n=>n.textContent)
  assert.deepEqual(ids,Array.from({length:61},(_,i)=>(kind==='users'?'user-':'permission-')+String(i).padStart(3,'0')))
  assert.equal(v.container.querySelector('[data-test="list-total"] [aria-current="true"]')?.textContent.trim(),'1')
 }finally{v.close()}
})
test('group cards independent native page-size 100 resets local page without route write races',async()=>{
 const {default:ApplicationGroupCards}=await import('../src/ApplicationGroupCards.vue')
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});await router.push('/groups')
 const items=Array.from({length:61},(_,i)=>({id:'group-'+String(i).padStart(3,'0'),displayName:'Group',active:true,counts:{}}))
 const container=document.createElement('div');document.body.append(container)
 const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(ApplicationGroupCards,{items,storageKey:'size-regression'}))})
 for(const [name,component]of Object.entries(DS))if(name.startsWith('Ks')&&component&&typeof component==='object')app.component(name,component)
 const i18n=createI18n({legacy:false,locale:'en',messages});await DS.registerDesignSystemI18n(i18n);app.use(router);app.use(i18n);app.provide('rbac-portals',document.body);app.mount(container)
 try{
  await tick();click(container.querySelector('.kel-pagination .btn-next'));await tick();assert.equal(container.querySelector('[data-group-id]')?.getAttribute('data-group-id'),'group-025')
  click(container.querySelector('.kel-pagination .kel-select__wrapper'));await tick();const options=[...document.querySelectorAll('[role="option"]')].filter(n=>n.textContent.trim()==='100/page');assert.equal(options.length,1);click(options[0]);await tick()
  assert.deepEqual([...container.querySelectorAll('[data-group-id]')].map(n=>n.getAttribute('data-group-id')),items.map(x=>x.id));assert.equal(container.querySelector('.kel-pagination [aria-current="true"]')?.textContent.trim(),'1');assert.equal(router.currentRoute.value.fullPath,'/groups')
 }finally{app.unmount();container.remove()}
})
for(const level of ['top','group'])test('App '+level+' navigation uses published box tabs and preserves real tab switching',async()=>{
 const v=await mountScenario(level==='top'?'application-groups':'application-groups/g/scopes/a',level==='top'?'groups':'scope')
 try{
  const tabs=v.container.querySelector('main > .kel-tabs');assert(tabs,'native App tabs absent');assert(tabs.classList.contains('kel-tabs--box'),'App must consume public type=box, not default14px tabs or custom CSS')
  const items=[...tabs.querySelectorAll('[role="tab"]')];assert.equal(items.length,level==='top'?2:4);assert.equal(items.filter(n=>n.getAttribute('aria-selected')==='true').length,1)
  const target=level==='top'?'users':'applications';const button=items.find(n=>n.id.endsWith('-'+target));assert(button,'native target tab missing');click(button)
  await wait(()=>v.state.context.path.startsWith(level==='top'?'users':'application-groups/g/applications')&&v.container.querySelector('main').getAttribute('aria-busy')==='false','native tab switch did not reach authorized content')
  const updated=v.container.querySelector('main > .kel-tabs');assert(updated.classList.contains('kel-tabs--box'));assert.equal(updated.querySelector('[aria-selected="true"]')?.id.endsWith('-'+target),true)
 }finally{v.close()}
})
test.after(()=>{hooks.deregister();window.happyDOM.abort()})
