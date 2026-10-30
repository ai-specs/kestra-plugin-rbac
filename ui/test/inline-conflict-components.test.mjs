import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript,compileTemplate} from '@vue/compiler-sfc'

// Real Vue SFCs and published design-system/Element Plus DOM. No network or browser.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
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
const {default:App}=await import('../src/App.vue')
const {default:InlineDetails}=await import('../src/InlineDetails.vue')
const {default:EditorDrawer}=await import('../src/EditorDrawer.vue')
const {default:KsButton}=await import('@kestra-io/design-system/components/Basic/KsButton/KsButton')
const {default:KsDrawer}=await import('@kestra-io/design-system/components/Feedback/KsDrawer')
const {default:KsDialog}=await import('@kestra-io/design-system/components/Feedback/KsDialog')
const {default:DeleteConfirmation}=await import('../src/DeleteConfirmation.vue')
const {createEditorState,openEditor}=await import('../src/editor-state.js')
const messages=JSON.parse(readFileSync(new URL('../src/translations/en.json',import.meta.url),'utf8'))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,0));await Vue.nextTick()}
const mount=(component,{router}={})=>{
  const container=document.createElement('div');document.body.append(container)
  const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(component))})
  app.component('KsSelect',KsSelect);app.component('KsOption',KsOption);app.component('KsButton',KsButton);app.component('KsDrawer',KsDrawer);app.component('KsDialog',KsDialog);app.provide('rbac-portals',container)
  app.use(createI18n({legacy:false,locale:'en',messages}))
  if(router)app.use(router)
  app.mount(container)
  return {container,close(){app.unmount();container.remove()}}
}
const disabled=node=>node.disabled||Boolean(node.closest('fieldset[disabled]'))
const click=node=>{assert(node);if(!disabled(node))node.click()}

for(const kind of ['group','scope','application'])test('inline '+kind+': conflict locks fields/save, cancel remains focusable, busy blocks every action',async()=>{
  const draft=Vue.reactive({_kind:kind,revision:1,key:'reader',name:'Draft',displayName:'Draft',clientId:'client',description:'unsaved'})
  const state=Vue.reactive({busy:false,conflicted:true});let saved=0,cancelled=0
  const view=mount({render:()=>Vue.h(InlineDetails,{draft,disabled:state.busy||state.conflicted,busy:state.busy,onSave:()=>saved++,onCancel:()=>cancelled++})})
  try {
    await tick();const form=view.container.querySelector('form'),buttons=form.querySelectorAll('button'),save=buttons[0],cancel=buttons[1]
    assert(disabled(form.querySelector('textarea')));assert(disabled(save))
    assert.equal(disabled(cancel),false,'conflict must not disable cancel')
    cancel.focus();assert.equal(document.activeElement,cancel);click(cancel);assert.equal(cancelled,1)
    form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saved,0,'programmatic submit must also remain blocked')
    assert.equal(draft.description,'unsaved')
    state.busy=true;await tick();assert(disabled(cancel));click(cancel);assert.equal(cancelled,1)
    form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saved,0)
    state.busy=false;state.conflicted=false;await tick();assert.equal(disabled(save),false)
    form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saved,1)
  }finally{view.close()}
})

for(const kind of ['group','user','permission','subject'])test('drawer '+kind+': conflict keeps cancel/discard available while saving blocks close and submit',async()=>{
  const state=Vue.reactive(createEditorState());const conflict=Vue.ref(true);let saves=0
  const view=mount({render:()=>Vue.h(EditorDrawer,{state,title:'Edit',conflicted:conflict.value,onSave:()=>saves++},{default:()=>Vue.h('input',{value:state.draft?.name})})})
  try {
    openEditor(state,kind,{name:'original'});state.draft.name='unsaved';await tick()
    const drawer=view.container.querySelector('[data-test="drawer-'+kind+'"]');assert(drawer)
    const cancel=drawer.querySelector('.kel-drawer__footer button:first-child'),save=drawer.querySelector('[data-test="drawer-save"]')
    assert(disabled(drawer.querySelector('input')));assert(disabled(save));assert.equal(disabled(cancel),false)
    cancel.focus();assert.equal(document.activeElement,cancel);click(cancel);await tick();assert.equal(state.discard,true);assert.equal(state.draft.name,'unsaved')
    const dialog=view.container.querySelector('[data-test="discard-confirm"]');assert(dialog)
    click(dialog.querySelector('button:not(.kel-button--danger)'));await tick();assert.equal(state.discard,false);assert.equal(state.draft.name,'unsaved')
    state.saving=true;await tick();assert(disabled(cancel));assert.equal(drawer.querySelector('.kel-drawer__close-btn'),null,'native close is hidden while saving; beforeClose must still reject');assert.equal(drawer.querySelector('.kel-drawer__header .is-link').disabled,false)
    const snapshot=JSON.stringify(state),maximize=drawer.querySelector('.kel-drawer__header .is-link')
    click(maximize);await tick();assert(drawer.classList.contains('full-screen'));click(maximize);await tick();assert(!drawer.classList.contains('full-screen'))
    assert.equal(JSON.stringify(state),snapshot,'saving maximize must not mutate the draft or editor state');assert.equal(saves,0);assert(disabled(drawer.querySelector('input')));assert(disabled(save))
    drawer.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));assert.equal(saves,0)
    click(cancel);assert.equal(state.discard,false)
    state.saving=false;await tick();click(cancel);await tick()
    click(view.container.querySelector('[data-test="discard-confirm"] .kel-button--danger'));await tick();assert.equal(state.kind,'');assert.equal(state.draft,null)
  }finally{view.close()}
})

// Actual inline component plus actual confirmation implements the parent dirty/discard contract.
test('inline conflict cancellation retains draft on keep-editing and explicitly discards on confirmation',async()=>{
  const state=Vue.reactive({draft:{_kind:'scope',key:'read',name:'Draft',description:'unsaved',revision:1},discard:false,busy:false})
  const view=mount({render:()=>Vue.h('main',[
    state.draft&&Vue.h(InlineDetails,{draft:state.draft,disabled:true,busy:state.busy,onCancel:()=>{state.discard=true}}),
    Vue.h(DeleteConfirmation,{open:state.discard,busy:state.busy,discard:true,label:'Discard',onCancel:()=>{state.discard=false},onConfirm:()=>{state.draft=null;state.discard=false}})
  ])})
  try {
    await tick();click(view.container.querySelector('.rbac-inline-form .buttons button:last-child'));await tick();assert.equal(state.discard,true)
    click(view.container.querySelector('[data-test="discard-confirm"] button:not(.kel-button--danger)'));await tick();assert.equal(state.draft.description,'unsaved');assert.equal(state.discard,false)
    click(view.container.querySelector('.rbac-inline-form .buttons button:last-child'));await tick();click(view.container.querySelector('[data-test="discard-confirm"] .kel-button--danger'));await tick()
    assert.equal(state.draft,null);assert.equal(view.container.querySelector('.rbac-inline-form'),null)
  }finally{view.close()}
})

for(const status of [409,412])test('App scope '+status+': real failed response retains draft; cancel/keep-editing and refresh/discard recover',async()=>{
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
  const requests=[];let stored={key:'read',name:'Reader',description:'original',revision:1},revision=1
  const response=(value,status=200)=>({ok:status<400,status,json:async()=>structuredClone(value)})
  const context={apiBase:'/api/v1/main',path:'application-groups/g/scopes/read',fullPath:'application-groups/g/scopes/read',tenant:'main',signal:new AbortController().signal,
    navigate:async()=>{throw new Error('unexpected host navigation')},request:async(path,options={})=>{
      requests.push({path,method:options.method??'GET',body:options.body})
      if(options.method==='PUT')return response({code:status===409?'scope_name_exists':'revision_conflict'},status)
      if(path.includes('/capabilities'))return response({allowed:true,identityManagement:true,identityRead:true,userDirectory:false,applicationGroupId:'g'})
      const relative=path.split('/application-groups/g')[1]??''
      if(relative==='')return response({id:'g',displayName:'Group',description:'',revision,authorizationVersion:1})
      const items=relative.startsWith('/scopes')?[stored]:[]
      return response({items,total:items.length,page:1,size:100,directoryRevision:revision})
    }}
  const view=mount({render:()=>Vue.h(App,{context})},{router})
  try {
    for(let i=0;i<15&&!view.container.querySelector('[data-test="scope-detail"] > button');i++)await tick()
    const edit=()=>view.container.querySelector('[data-test="scope-detail"] > button')
    assert(edit(),'scope editor prerequisite did not load');click(edit());await tick()
    let textarea=view.container.querySelector('.rbac-inline-form textarea');textarea.value='unsaved draft';textarea.dispatchEvent(new Event('input',{bubbles:true}));await tick()
    const form=view.container.querySelector('.rbac-inline-form');form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await tick()
    const error=view.container.querySelector('[data-test="request-error"]');assert(error);assert(error.textContent.includes('changed'))
    assert.equal(document.activeElement,error,'failure focuses actual error alert')
    textarea=view.container.querySelector('.rbac-inline-form textarea');assert.equal(textarea.value,'unsaved draft');assert(disabled(textarea))
    assert(disabled(view.container.querySelector('[data-test="scope-save"]')))
    const mutations=()=>requests.filter(request=>request.method==='PUT')
    assert.equal(mutations().length,1)
    form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await tick();assert.equal(mutations().length,1)
    const cancel=view.container.querySelector('.rbac-inline-form .buttons button:last-child');assert.equal(disabled(cancel),false);cancel.focus();assert.equal(document.activeElement,cancel);click(cancel);await tick()
    let confirm=view.container.querySelector('[data-test="discard-confirm"]');assert(confirm)
    click(confirm.querySelector('button:not(.kel-button--danger)'));await tick()
    assert.equal(view.container.querySelector('.rbac-inline-form textarea').value,'unsaved draft')
    const readsBefore=requests.filter(request=>request.method==='GET').length
    stored={...stored,description:'fresh server value',revision:2};revision=2
    click(view.container.querySelector('[data-test="conflict-refresh"]'));await tick()
    assert.equal(requests.filter(request=>request.method==='GET').length,readsBefore,'refresh must wait for explicit discard')
    confirm=view.container.querySelector('[data-test="discard-confirm"]');click(confirm.querySelector('.kel-button--danger'))
    for(let i=0;i<10;i++)await tick()
    assert.equal(view.container.querySelector('.rbac-inline-form'),null)
    assert.equal(view.container.querySelector('[data-test="request-error"]'),null)
    assert(view.container.querySelector('[data-test="scope-detail"]').textContent.includes('fresh server value'))
    click(edit());await tick();assert.equal(disabled(view.container.querySelector('[data-test="scope-save"]')),false)
    assert.equal(mutations().length,1,'cancel/discard/refresh never retries mutation')
  }finally{view.close()}
})

test.after(()=>{hooks.deregister();window.happyDOM.abort()})
