import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {registerHooks} from 'node:module'
import {Window} from 'happy-dom'
import {parse,compileScript} from '@vue/compiler-sfc'

// Real Vue SFCs and published design-system/Element Plus DOM. No network or browser.
const window=new Window({url:'http://localhost/'})
for(const name of ['window','document','history','location','navigator','HTMLElement','HTMLInputElement','HTMLTextAreaElement','HTMLButtonElement','SVGElement','Element','Node','Event','MouseEvent','KeyboardEvent','MutationObserver','ResizeObserver','CustomEvent','getComputedStyle','requestAnimationFrame','cancelAnimationFrame'])
  Object.defineProperty(globalThis,name,{value:typeof window[name]==='function'&&['getComputedStyle','requestAnimationFrame','cancelAnimationFrame'].includes(name)?window[name].bind(window):window[name],configurable:true,writable:true})
const hooks=registerHooks({load(url,context,next){
  if(/\.(?:png|svg|woff2?|ttf)$/.test(url))return {format:'module',source:'export default '+JSON.stringify(url),shortCircuit:true}
  if(url.endsWith('.css'))return {format:'module',source:'export default {}',shortCircuit:true}
  if(url.endsWith('.vue')) {
    const filename=fileURLToPath(url),{descriptor}=parse(readFileSync(filename,'utf8'),{filename})
    return {format:'module',source:compileScript(descriptor,{id:filename,inlineTemplate:true}).content,shortCircuit:true}
  }
  return next(url,context)
}})
const Vue=await import('vue')
const {createI18n}=await import('vue-i18n')
const {ElConfigProvider}=await import('element-plus')
const {default:AccessSidebar}=await import('../src/AccessSidebar.vue')
const messages=JSON.parse(readFileSync(new URL('../src/translations/en.json',import.meta.url),'utf8'))
const tick=async()=>{await Vue.nextTick();await new Promise(resolve=>setTimeout(resolve,0));await Vue.nextTick()}
for(const kind of ['scope','application'])test(kind+' sidebar synchronizes deep links, selection and directory changes without overriding manual paging or search',async()=>{
 const field=kind==='scope'?'key':'clientId',key=n=>'item-'+String(n).padStart(3,'0')
 const state=Vue.reactive({items:Array.from({length:61},(_,i)=>({[field]:key(i)})),selected:key(55)})
 const container=document.createElement('div');document.body.append(container)
 const app=Vue.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel'},()=>Vue.h(AccessSidebar,{...state,field,kind,title:kind,canManage:true,onSelect:item=>{state.selected=item[field]}}))})
 app.use(createI18n({legacy:false,locale:'en',messages}));app.mount(container)
 const current=()=>container.querySelector('[aria-current="true"] code')?.textContent
 const next=()=>container.querySelector('footer button:last-child')
 const previous=()=>container.querySelector('footer button:first-child')
 try {
  await tick();assert.equal(current(),key(55),'mounted deep link must be visible on page 3')
  previous().click();await tick();assert.equal(current(),undefined);assert(container.querySelector('footer').textContent.includes('2 / 3'))
  await tick();assert(container.querySelector('footer').textContent.includes('2 / 3'),'render must not force manual paging back')
  state.selected=key(5);await tick();assert.equal(current(),key(5))
  state.selected=key(55);await tick();assert.equal(current(),key(55))
  state.items=state.items.filter(item=>item[field]>=key(40));await tick();assert.equal(current(),key(55),'directory shrink must resynchronize selected position')
  state.items=Array.from({length:61},(_,i)=>({[field]:key(i)}));await tick();assert.equal(current(),key(55))
  state.items=state.items.filter(item=>item[field]!==key(55));state.selected=key(54);await tick();assert.equal(current(),key(54),'delete fallback must be visible')
  const input=container.querySelector('input');input.value='item-0';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  assert(container.querySelector('footer').textContent.includes('1 / 3'),'search starts on page 1 even if selection matches later page')
  next().click();await tick();assert(container.querySelector('footer').textContent.includes('2 / 3'))
  input.value='item-000';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();assert.equal(container.querySelectorAll('li').length,1);assert.equal(container.querySelector('li code').textContent,key(0))
  input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));await tick();assert(container.querySelector('footer').textContent.includes('1 / 3'))
  state.selected=key(60);await tick();assert.equal(current(),key(60))
 }finally{app.unmount();container.remove()}
})
