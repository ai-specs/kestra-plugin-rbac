import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync,readdirSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real Vue SFCs and published design-system/Element Plus DOM. No network or browser.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','localStorage','sessionStorage','Storage','StorageEvent','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
  Object.defineProperty(globalThis,name,{value:typeof window[name]==='function'&&['getComputedStyle','requestAnimationFrame','cancelAnimationFrame'].includes(name)?window[name].bind(window):window[name],configurable:true,writable:true})
const hooks=registerHooks({load(url,context,next){
  if(/\.(?:png|svg|woff2?|ttf)$/.test(url))return {format:'module',source:'export default '+JSON.stringify(url),shortCircuit:true}
  if(url.endsWith('.css'))return {format:'module',source:'export default {}',shortCircuit:true}
  if(url.endsWith('.vue')) {
    const filename=fileURLToPath(url),{descriptor}=parse(readFileSync(filename,'utf8'),{filename})
    const script=compileScript(descriptor,{id:filename,inlineTemplate:true})
    const source=descriptor.scriptSetup?script.content:script.content.replace('export default','const component =')+'\n'+compileTemplate({source:descriptor.template.content,filename,id:filename}).code.replace('export function render','function render')+'\ncomponent.render=render;export default component'
    return {format:'module',source,shortCircuit:true}
  }
  return next(url,context)
}})
const Vue=await import('vue')
const {createI18n}=await import('vue-i18n')
const {ElConfigProvider}=await import('element-plus')
const {KsSelect,KsOption}=await import('@kestra-io/design-system')
const {createMemoryHistory,createRouter}=await import('vue-router')
const {default:EditorDrawer}=await import('../src/EditorDrawer.vue')
const {default:KsButton}=await import('@kestra-io/design-system/components/Basic/KsButton/KsButton')
const {default:KsDrawer}=await import('@kestra-io/design-system/components/Feedback/KsDrawer')
const {default:KsDialog}=await import('@kestra-io/design-system/components/Feedback/KsDialog')
const {createEditorState,openEditor}=await import('../src/editor-state.js')
const messages=Object.assign({},...readdirSync(new URL('../src/translations/',import.meta.url)).filter(name=>name.endsWith('.json')).map(name=>JSON.parse(readFileSync(new URL('../src/translations/'+name,import.meta.url),'utf8'))))
const {nativeLocale}=await import('../src/native-locale.js')
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,0));await Vue.nextTick()}
const mount=(component,{router,locale='en'}={})=>{
  const container=document.createElement('div');document.body.append(container)
  const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel',locale:nativeLocale(locale)},()=>Vue.h(component))})
  app.component('KsSelect',KsSelect);app.component('KsOption',KsOption);app.component('KsButton',KsButton);app.component('KsDrawer',KsDrawer);app.component('KsDialog',KsDialog);app.provide('rbac-portals',container)
  app.use(createI18n({legacy:false,locale,messages}))
  if(router){app.use(router);for(const [name,component] of Object.entries({KsFilter,KsPopover,KsTooltip,KsDropdown,KsSearch,KsIcon}))app.component(name,component)}
  app.mount(container)
  return {container,close(){app.unmount();container.remove()}}
}
const disabled=node=>node.disabled||Boolean(node.closest('fieldset[disabled]'))
const click=node=>{assert(node);if(!disabled(node))node.click()}

test('native drawer owns title/header SVGs, footer/form association, body padding and reopen reset',async()=>{
 const state=Vue.reactive(createEditorState());let saves=0
 const view=mount({render:()=>Vue.h(EditorDrawer,{state,title:'Native edit',onSave:()=>saves++},{default:()=>Vue.h('input',{name:'native-field'})})})
 try{
  openEditor(state,'group',{name:'original'});await tick()
  let drawer=view.container.querySelector('.kel-drawer')
  assert(drawer.querySelector('.kel-drawer__header').textContent.includes('Native edit'))
  assert(drawer.querySelector('.kel-drawer__header .arrow-expand-icon svg path'))
  assert(drawer.querySelector('.kel-drawer__close-btn svg path'))
  assert(!drawer.querySelector('.rbac-drawer-header'))
  const form=drawer.querySelector('form.ks-horizontal.kel-form'),save=drawer.querySelector('[data-test="drawer-save"]')
  assert(form);assert(drawer.querySelector('.kel-drawer__footer').contains(save));assert(!form.contains(save))
  assert.equal(save.getAttribute('form'),form.id);assert(save.querySelector('svg path'),'save uses native icon')
  click(save);await tick();assert.equal(saves,1,'native footer submit must reach its associated form')
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saves,2)
  click(drawer.querySelector('.kel-drawer__header .is-link'));await tick();assert(drawer.classList.contains('full-screen'));assert(drawer.querySelector('.arrow-collapse-icon svg'))
  state.saving=true;await tick();assert.equal(drawer.querySelector('.kel-drawer__close-btn'),null)
  const before=JSON.stringify(state),maximize=drawer.querySelector('.kel-drawer__header .is-link')
  assert.equal(maximize.disabled,false);click(maximize);await tick();assert(!drawer.classList.contains('full-screen'));assert(drawer.querySelector('.arrow-expand-icon svg'))
  click(maximize);await tick();assert(drawer.classList.contains('full-screen'));assert(drawer.querySelector('.arrow-collapse-icon svg'))
  assert.equal(JSON.stringify(state),before,'saving maximize only changes native presentation');assert.equal(saves,2);assert(disabled(drawer.querySelector('input')));assert(save.disabled)
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saves,2)
  state.saving=false;await tick();click(drawer.querySelector('.kel-drawer__close-btn'));await tick();assert.equal(state.kind,'')
  openEditor(state,'user',{name:'other'});await tick();drawer=view.container.querySelector('.kel-drawer');assert(!drawer.classList.contains('full-screen'));assert(drawer.querySelector('.arrow-expand-icon svg'))
 }finally{view.close()}
})
test('native drawer CSS leaves padding/header/width to published component and counts own explicit token gap',()=>{
 const css=readFileSync(new URL('../src/style.css',import.meta.url),'utf8')
 assert.doesNotMatch(css,/\.rbac-editor-drawer[^}]*padding|\.rbac-drawer-header|\.rbac-drawer-form\s*\{[^}]*padding/)
 assert.match(css,/\.rbac-card-count-label\s*\{[^}]*gap: var\(--ks-spacing-1\)/)
 const cards=readFileSync(new URL('../src/ApplicationGroupCards.vue',import.meta.url),'utf8')
 assert.match(cards,/class="rbac-card-count-label"/)
})
const {default:ApplicationGroupCards}=await import('../src/ApplicationGroupCards.vue')
const {default:KsFilter}=await import('@kestra-io/design-system/components/Data/KsDataTable/KsFilter')
const {KsPopover,KsTooltip,KsDropdown,KsSearch,KsIcon}=await import('@kestra-io/design-system')
test('actual native card buttons retain spaced accessible text, counts, destinations and unavailable/busy locks',async()=>{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});await router.push('/groups')
 const busy=Vue.ref(false),events=[],items=[{id:'g',displayName:'Group',active:true,counts:{scopes:54,applications:29,subjects:54}},{id:'unknown',displayName:'Unknown',counts:{scopes:null,applications:null,subjects:null}}]
 const view=mount({render:()=>Vue.h(ApplicationGroupCards,{items,busy:busy.value,storageKey:'native-counts',onOpen:item=>events.push(item.id),onSection:(item,tab)=>events.push([item.id,tab])})},{router})
 try{
  await tick();const card=view.container.querySelector('[data-group-id="g"]')
  for(const [field,count,label,tab] of [['scopes',54,messages.en.scopes,'scopes'],['applications',29,messages.en.clients,'applications'],['subjects',54,'subject','scope-grants']]){
   const button=card.querySelector('[data-test="group-count-'+field+'"]'),wrapper=button.querySelector('.rbac-card-count-label')
   assert(wrapper);assert.equal(wrapper.querySelector('b').textContent,String(count));assert.equal(button.textContent.trim(),count+' '+label);click(button);assert.deepEqual(events.at(-1),['g',tab])
  }
  click(card.querySelector('.application-group-card-link'));assert.equal(events.at(-1),'g')
  for(const button of view.container.querySelectorAll('[data-group-id="unknown"] .rbac-card-counts button')){assert(button.disabled);assert(button.textContent.includes('—'))}
  busy.value=true;await tick();const before=events.length;for(const button of card.querySelectorAll('button')){assert(button.disabled);click(button)}assert.equal(events.length,before)
 }finally{view.close()}
})
const {nodeOps,patchProp}=await import('@vue/runtime-dom')
const {ID_INJECTION_KEY}=await import('element-plus')
const {createShadowOverlayScope,createShadowRenderer,shadowOverlayComponents}=await import('../../.integration/ui-sdk/shadow-overlays.mjs')
async function shadowDrawer(){
 const host=document.createElement('div');document.body.append(host);const root=host.attachShadow({mode:'open'}),content=document.createElement('div'),portal=document.createElement('div');root.append(content,portal)
 // happy-dom has no layout. Model visibility for the SDK focus algorithm only;
 // no assertion below is evidence of browser geometry or narrow-screen styling.
 const original=window.HTMLElement.prototype.getClientRects
 window.HTMLElement.prototype.getClientRects=function(){return this.closest('[hidden]')?[]:[{width:100,height:30}]}
 const controller=new AbortController(),scope=createShadowOverlayScope({container:host,content,portal,signal:controller.signal,fallback:()=>content.querySelector('[data-focus-fallback]')})
 const state=Vue.reactive(createEditorState());let saves=0
 const renderer=createShadowRenderer({...Vue,nodeOps,patchProp},scope,{idInjectionKey:ID_INJECTION_KEY,namespace:'kel'})
 const app=renderer.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>[
  Vue.h('button',{'data-focus-fallback':'',onClick:()=>openEditor(state,'group',{name:'original'})},'Open'),
  Vue.h(EditorDrawer,{state,title:'Shadow native',onSave:()=>saves++},{default:()=>Vue.h('input',{name:'draft',value:state.draft?.name,onInput:event=>{state.draft.name=event.target.value}})})
 ])})
 app.provide('rbac-portals',portal);app.component('KsButton',KsButton)
 for(const [name,component] of Object.entries(shadowOverlayComponents(Vue,{KsDrawer,KsDialog},scope)))app.component(name,component)
 app.use(createI18n({legacy:false,locale:'en',messages}));app.mount(content);await tick()
 const trigger=content.querySelector('button');trigger.focus();click(trigger);await tick()
 return {state,root,portal,content,trigger,scope,saves:()=>saves,close(){app.unmount();scope.dispose();controller.abort();host.remove();window.HTMLElement.prototype.getClientRects=original}}
}
const key=(target,value,shiftKey=false)=>target.dispatchEvent(new KeyboardEvent('keydown',{key:value,shiftKey,bubbles:true,composed:true,cancelable:true}))
test('published shadow adapter keeps native header and footer in focus ring; Escape closes then restores trigger/fallback',async()=>{
 const view=await shadowDrawer()
 try{
  let drawer=view.portal.querySelector('[data-test="drawer-group"]');assert(drawer.classList.contains('kel-drawer'));assert(drawer.contains(drawer.querySelector('.kel-drawer__header')));assert(drawer.contains(drawer.querySelector('.kel-drawer__footer')))
  const first=drawer.querySelector('.kel-drawer__header button'),last=drawer.querySelector('[data-test="drawer-save"]')
  first.focus();key(first,'Tab',true);await tick();assert.equal(view.root.activeElement,last)
  last.focus();key(last,'Tab');await tick();assert.equal(view.root.activeElement,first)
  key(first,'Escape');await tick();assert.equal(view.state.kind,'');assert.equal(view.root.activeElement,view.trigger)
  click(view.trigger);await tick();drawer=view.portal.querySelector('.kel-drawer');const input=drawer.querySelector('input');input.focus();key(input,'Escape');await tick();assert.equal(view.state.kind,'');assert.equal(view.root.activeElement,view.trigger)
  click(view.trigger);await tick();view.trigger.remove();const fallback=document.createElement('button');fallback.setAttribute('data-focus-fallback','');view.content.append(fallback)
  key(view.portal.querySelector('input'),'Escape');await tick();assert.equal(view.root.activeElement,fallback)
 }finally{view.close()}
})
test('published native drawer dirty nested Escape keeps draft, saving rejects Escape/mask/close and submit',async()=>{
 const view=await shadowDrawer()
 try{
  let input=view.portal.querySelector('input');input.value='unsaved';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();input.focus();key(input,'Escape');await tick();assert(view.state.discard);assert.equal(view.scope.inspect().modals.length,2)
  const dialog=view.portal.querySelector('[data-test="discard-confirm"]');key(dialog.querySelector('button'),'Escape');await tick();assert.equal(view.state.discard,false);assert.equal(view.state.kind,'group');assert.equal(view.state.draft.name,'unsaved');assert.equal(view.scope.inspect().modals.length,1)
  view.state.saving=true;await tick();const drawer=view.portal.querySelector('.kel-drawer');assert.equal(drawer.querySelector('.kel-drawer__close-btn'),null)
  const before=JSON.stringify(view.state),maximize=drawer.querySelector('.kel-drawer__header button')
  assert.equal(maximize.disabled,false);click(maximize);await tick();assert(drawer.classList.contains('full-screen'));click(maximize);await tick();assert(!drawer.classList.contains('full-screen'))
  assert.equal(JSON.stringify(view.state),before);assert.equal(view.saves(),0);assert(disabled(drawer.querySelector('input')));assert(drawer.querySelector('[data-test="drawer-save"]').disabled)
  key(drawer.querySelector('input'),'Escape');await tick();assert.equal(view.state.kind,'group');assert.equal(view.state.discard,false)
  const mask=drawer.closest('.kel-overlay');mask.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));mask.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));mask.click();await tick();assert.equal(view.state.kind,'group');assert.equal(view.state.discard,false)
  drawer.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(view.saves(),0)
  view.state.saving=false;await tick();click(drawer.querySelector('.kel-drawer__close-btn'));await tick();assert(view.state.discard);click(view.portal.querySelector('[data-test="discard-confirm"] .kel-button--danger'));await tick();assert.equal(view.state.kind,'');assert.equal(view.state.draft,null)
 }finally{view.close()}
})
const {default:UserFields}=await import('../src/UserFields.vue')
const {default:PermissionFields}=await import('../src/PermissionFields.vue')
test('public native forms translate real labels in all published locales and retain immutable permission key/account identity',async()=>{
 for(const locale of Object.keys(messages)){
  for(const kind of ['user','permission']){
   const state=Vue.reactive(createEditorState());openEditor(state,kind,kind==='user'?{subject:'existing-user',name:'Name',email:'user@example.test',revision:1}:{key:'immutable-key',name:'Permission',details:'Text only',scopeKeys:[],revision:1})
   const view=mount({render:()=>Vue.h(EditorDrawer,{state,title:messages[locale].edit},{default:()=>kind==='user'?Vue.h(UserFields,{draft:state.draft}):Vue.h(PermissionFields,{draft:state.draft,scopes:[],implementationKind:'external-declaration',editing:true})})},{locale})
   try{
    await tick();const drawer=view.container.querySelector('.kel-drawer'),labels=[...drawer.querySelectorAll('.kel-form-item__label')].map(label=>label.textContent)
    assert(labels.includes(messages[locale][kind==='user'?'name':'permissionName']),locale+' real translated label')
    for(const label of drawer.querySelectorAll('label.kel-form-item__label[for]'))assert(drawer.querySelector('[id="'+label.htmlFor+'"]'),'native label must name a real field')
    if(kind==='user'){assert.equal(drawer.querySelector('output').textContent,'existing-user');assert.equal(drawer.querySelector('[name="username"]'),null)}
    else{assert(drawer.querySelector('[name="permission-key"]').disabled);assert.equal(drawer.querySelector('[name="permission-key"]').value,'immutable-key')}
    assert.equal(drawer.querySelector('.kel-drawer__close-btn').getAttribute('aria-label'),nativeLocale(locale).el.drawer.close)
   }finally{view.close()}
  }
 }
})
test('native clean mask closes; readonly and pending App save stay locked while native maximize remains presentation-only',async()=>{
 const view=await shadowDrawer()
 try{
  let drawer=view.portal.querySelector('.kel-drawer'),mask=drawer.closest('.kel-overlay')
  assert(mask);mask.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));mask.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));mask.click();await tick();assert.equal(view.state.kind,'','mask fixture really reaches native close path')
 }finally{view.close()}
 const state=Vue.reactive(createEditorState());openEditor(state,'group',{name:'original'});let saves=0
 const readonly=mount({render:()=>Vue.h(EditorDrawer,{state,title:'Readonly',disabled:true,onSave:()=>saves++},{default:()=>Vue.h('input')})})
 try{
  await tick();const drawer=readonly.container.querySelector('.kel-drawer');assert(disabled(drawer.querySelector('input')));assert(drawer.querySelector('[data-test="drawer-save"]').disabled)
  drawer.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saves,0)
  state.saving=true;await tick();const before=JSON.stringify(state),maximize=drawer.querySelector('.kel-drawer__header button');assert.equal(maximize.disabled,false)
  click(maximize);await tick();assert(drawer.classList.contains('full-screen'));assert.equal(JSON.stringify(state),before);assert.equal(saves,0)
 }finally{readonly.close()}
 await pendingAppSavePresentationOnly()
})
test('original public KsButton stays globally unwrapped and preserves public props/events including bare disabled',async()=>{
 const lifecycle=readFileSync(new URL('../src/lifecycle.js',import.meta.url),'utf8'),editor=readFileSync(new URL('../src/EditorDrawer.vue',import.meta.url),'utf8')
 assert.doesNotMatch(lifecycle+'\n'+editor,/NativeDrawerButton|rbac-drawer-saving|KsDrawer\.setup|extends\s*:/)
 assert.match(lifecycle,/Object\.entries\(\{KsButton,/)
 let clicks=0
 const view=mount({render:()=>Vue.h(KsButton,{disabled:'',link:true,'native-type':'submit',form:'public-form',onClick:()=>clicks++},()=> 'Native')})
 try{await tick();const button=view.container.querySelector('button');assert(button.disabled);assert(button.classList.contains('is-link'));assert.equal(button.type,'submit');assert.equal(button.getAttribute('form'),'public-form');click(button);assert.equal(clicks,0)}finally{view.close()}
})
const {default:App}=await import('../src/App.vue')
async function pendingAppSavePresentationOnly(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});await router.push('/application-groups')
 const requests=[],navigations=[],response=(body,status=200)=>({ok:status<400,status,json:async()=>structuredClone(body)})
 let release
 const pending=new Promise(resolve=>{release=resolve})
 const context={apiBase:'/api/v1/main',path:'application-groups',fullPath:'application-groups',tenant:'main',signal:new AbortController().signal,
  navigate:async(...args)=>{navigations.push(args)},request:async(path,options={})=>{
   requests.push({path,method:options.method??'GET',body:options.body})
   if(options.method==='POST')return pending
   if(path.includes('/capabilities'))return response({allowed:true,identityRead:true,identityManagement:true,userDirectory:false})
   return response({items:[],total:0,page:1,size:100,directoryRevision:1})
  }}
 const view=mount({render:()=>Vue.h(App,{context})},{router})
 try{
  for(let i=0;i<30&&!view.container.querySelector('main > header button');i++)await tick()
  click(view.container.querySelector('main > header button'));await tick()
  const drawer=view.container.querySelector('[data-test="drawer-group"]');assert(drawer)
  const fields=[...drawer.querySelectorAll('input,textarea')]
  assert.equal(fields.length,3)
  for(const [i,value]of ['presentation-only','Pending group','Unchanged pending draft'].entries()){fields[i].value=value;fields[i].dispatchEvent(new Event('input',{bubbles:true}))}
  await tick();click(drawer.querySelector('[data-test="drawer-save"]'))
  for(let i=0;i<30&&!requests.some(request=>request.method==='POST');i++)await tick()
  assert.equal(requests.filter(request=>request.method==='POST').length,1);await tick()
  assert.equal(drawer.getAttribute('aria-busy'),'true');assert.equal(drawer.querySelector('.kel-drawer__close-btn'),null)
  const values=fields.map(field=>field.value),requestSnapshot=JSON.stringify(requests),navigationSnapshot=JSON.stringify(navigations)
  const maximize=drawer.querySelector('.kel-drawer__header .is-link');assert.equal(maximize.disabled,false)
  for(const maximized of [true,false,true]){click(maximize);await tick();assert.equal(drawer.classList.contains('full-screen'),maximized);assert.deepEqual(fields.map(field=>field.value),values);assert.equal(JSON.stringify(requests),requestSnapshot);assert.equal(JSON.stringify(navigations),navigationSnapshot)}
  for(const field of fields)assert(disabled(field),'pending save prevents user editing')
  for(const button of drawer.querySelectorAll('.kel-drawer__footer button')){assert(button.disabled);click(button)}
  drawer.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await tick()
  assert.equal(JSON.stringify(requests),requestSnapshot,'repeat submit does not issue a second request');assert.equal(JSON.stringify(navigations),navigationSnapshot)
  assert.deepEqual(fields.map(field=>field.value),values);assert.equal(drawer.getAttribute('aria-busy'),'true')
 }finally{release(response({code:'test_dependency_unavailable'},503));await tick();view.close()}
}
test.after(()=>{hooks.deregister();window.happyDOM.abort()})
