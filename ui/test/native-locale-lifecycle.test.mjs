import test from 'node:test'
import assert from 'node:assert/strict'
import {registerHooks} from 'node:module'
import * as Vue from 'vue'
import {useLocale} from 'element-plus/es/hooks/use-locale/index.mjs'
import en from 'element-plus/es/locale/lang/en.mjs'
import de from 'element-plus/es/locale/lang/de.mjs'
import es from 'element-plus/es/locale/lang/es.mjs'
import fr from 'element-plus/es/locale/lang/fr.mjs'
import hi from 'element-plus/es/locale/lang/hi.mjs'
import it from 'element-plus/es/locale/lang/it.mjs'
import ja from 'element-plus/es/locale/lang/ja.mjs'
import ko from 'element-plus/es/locale/lang/ko.mjs'
import pl from 'element-plus/es/locale/lang/pl.mjs'
import pt from 'element-plus/es/locale/lang/pt.mjs'
import ptBR from 'element-plus/es/locale/lang/pt-br.mjs'
import ru from 'element-plus/es/locale/lang/ru.mjs'
import zhCN from 'element-plus/es/locale/lang/zh-cn.mjs'

const node = (type, text = '') => ({type,text,children:[],parent:null,props:{},classList:{add(){},remove(){}},append(...items){for(const item of items){item.parent=this;this.children.push(item)}},replaceChildren(){this.children=[]},querySelector(){return null}})
const renderer=Vue.createRenderer({
  createElement:type=>node(type),createText:text=>node('text',text),createComment:text=>node('comment',text),
  setText:(target,text)=>{target.text=text},setElementText:(target,text)=>{target.text=text},
  parentNode:target=>target.parent,nextSibling:target=>target.parent?.children[target.parent.children.indexOf(target)+1]??null,
  patchProp:(target,key,old,value)=>{target.props[key]=value},
  insert(target,parent,anchor=null){if(target.parent)target.parent.children.splice(target.parent.children.indexOf(target),1);const index=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(index<0?parent.children.length:index,0,target);target.parent=parent},
  remove(target){if(target.parent)target.parent.children.splice(target.parent.children.indexOf(target),1);target.parent=null}
})
let translated
globalThis.__rbacLocaleTest={renderer,probe:Vue.defineComponent({setup(){
  const {t,lang}=useLocale()
  return ()=>{translated={language:lang.value,empty:t('el.select.noData'),noMatch:t('el.select.noMatch'),loading:t('el.select.loading')};return Vue.h('span',translated.empty)}
}})}
const hooks=registerHooks({
  load(url,context,next){const result=next(url,context);return url.endsWith('/src/lifecycle.js')?{...result,source:String(result.source).replace("import.meta.glob('./translations/*.json', {eager: true, import: 'default'})",'{}')}:result},
  resolve(specifier,context,next){
    if(context.parentURL?.endsWith('/src/lifecycle.js')) {
      let source
      if(specifier==='./App.vue')source='export default globalThis.__rbacLocaleTest.probe'
      else if(specifier==='@kestra-io/design-system')source='export const registerDesignSystemI18n=async()=>{};export const KsButton={},KsInput={},KsSearch={},KsPopover={},KsTooltip={},KsIcon={},KsSelect={},KsOption={},KsTag={},KsSegmented={},KsDropdown={},KsDropdownMenu={},KsDropdownItem={};'
      else if(specifier.startsWith('@kestra-io/design-system/'))source='export default {}'
      else if(specifier.endsWith('/shadow-overlays.mjs'))source='export const createShadowOverlayScope=()=>({dispose(){}});export const createShadowRenderer=()=>globalThis.__rbacLocaleTest.renderer;export const shadowOverlayComponents=()=>({});'
      if(source)return {url:'data:text/javascript,'+encodeURIComponent(source),shortCircuit:true}
    }
    return next(specifier,context)
  }
})
const {mountPage}=await import('../src/lifecycle.js')
hooks.deregister()

test('T11: real Element Plus provider inherits every host locale and updates native Select translations',async()=>{
  const previous=globalThis.document
  globalThis.document={createElement:type=>node(type)}
  const controller=new AbortController(),container=node('container');container.shadowRoot=node('shadow-root')
  const initial={contractVersion:1,signal:controller.signal,styles:[],path:'users',fullPath:'users',locale:'en',theme:'light',apiBase:'/api/v1/main'}
  let mounted
  try {
    mounted=await mountPage(container,initial);await Vue.nextTick()
    const locales={en,de,es,fr,hi,it,ja,ko,pl,pt,pt_BR:ptBR,ru,zh_CN:zhCN,'pt-BR':ptBR,'zh-CN':zhCN,unknown:en}
    for(const [locale,expected] of Object.entries(locales)) {
      mounted.update({...initial,locale});await Vue.nextTick()
      assert.deepEqual(translated,{language:expected.name,empty:expected.el.select.noData,noMatch:expected.el.select.noMatch,loading:expected.el.select.loading},'native provider locale '+locale)
    }
  }finally{mounted?.unmount();controller.abort();globalThis.document=previous;delete globalThis.__rbacLocaleTest}
})
