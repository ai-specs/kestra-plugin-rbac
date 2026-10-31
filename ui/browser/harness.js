import * as Vue from 'vue'
import {nodeOps, patchProp} from '@vue/runtime-dom'
import {createI18n} from 'vue-i18n'
import {ElConfigProvider, ID_INJECTION_KEY} from 'element-plus'
import {registerDesignSystemI18n} from '@kestra-io/design-system'
import KsDrawer from '@kestra-io/design-system/components/Feedback/KsDrawer'
import KsButton from '@kestra-io/design-system/components/Basic/KsButton/KsButton'
import ContentSave from 'vue-material-design-icons/ContentSave.vue'
import {createShadowOverlayScope, createShadowRenderer, shadowOverlayComponents} from '../../.integration/ui-sdk/shadow-overlays.mjs'
import {nativeLocale} from '../src/native-locale.js'
import {resolveLocale} from '../../.integration/ui-sdk/locales.mjs'
const messages = Object.assign({}, ...Object.values(import.meta.glob('../src/translations/*.json', {eager:true, import:'default'})))
const qs = new URLSearchParams(location.search)
const locale = resolveLocale(qs.get('locale') || 'en')
let theme = qs.get('theme') || 'light'
const effective = () => theme === 'system' ? matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light' : theme
const group = {id:'team',displayName:'Team display',description:'Browser fixture group',issuer:'https://fixture.invalid',active:true,builtIn:true,revision:1,counts:{scopes:1,applications:1,subjects:1}}
const user = {subject:'alice',username:'alice',name:'Alice fixture',email:'alice@example.invalid',active:true,revision:1}
const application = {clientId:'client',implementationKind:'external-declaration',audiences:[],supportedScopeKeys:['read'],description:'Fixture application',revision:1}
const permission = {implementationKind:'external-declaration',parentRevision:1,key:'read',name:'Read fixture',scopeKeys:['read'],details:'Visible permission summary\nSecond permission line',revision:1}
let scenario = qs.get('state') || 'normal', release, saveRelease
let holdWrites = false
const requests = []
const response = (data,status=200) => ({ok:status<400,status,json:async()=>structuredClone(data)})
const page = items => response({items: scenario === 'empty' ? [] : items,total:scenario === 'empty'?0:items.length,page:1,size:100,directoryRevision:1,parentRevision:1,revision:1})
async function request(path, options={}) {
  const url = new URL(path,location.origin), route = url.pathname.split('/extensions/rbac')[1]
  const method = options.method || 'GET'
  requests.push({route,method,body:options.body})
  if (scenario === 'loading') await new Promise(resolve=>{release=resolve})
  if (scenario === 'error') return response({code:'dependency_unavailable'},503)
  if (scenario === 'denied') return response({code:'forbidden'},403)
  if (scenario === 'reauthentication') return response({code:'session_expired'},401)
  if (method !== 'GET') {
    if (holdWrites) await new Promise(resolve=>{saveRelease=resolve})
    const input = options.body ? JSON.parse(options.body) : {}
    if (route === '/users/profile') Object.assign(user,input)
    if (route === '/users') return response({subject:input.username,revision:1,temporaryPassword:'fixture-password-only'})
    if (route.endsWith('/rotate')) return response({clientId:'client',credentialRevision:2,clientSecret:'fixture-secret-only'})
    return response({...input,revision:2})
  }
  if (route === '/capabilities') return response({allowed:true,identityRead:true,identityManagement:true,userDirectory:true,localPolicyRead:true,localPolicyManagement:true})
  if (route === '/application-groups') return page([group])
  if (route === '/application-groups/team') return response(group)
  if (route.endsWith('/scopes')) return page([{key:'read',name:'Read scope',revision:1}])
  if (route.endsWith('/scope-grants')) return page([{issuer:group.issuer,subject:'alice',scopeKeys:['read'],revision:1}])
  if (route.endsWith('/applications')) return page([application])
  if (route.endsWith('/permissions')) return page([permission])
  if (route.endsWith('/mutation-metadata')) return response({revision:1})
  if (route.endsWith('/credentials')) return response({clientId:'client',clientType:'confidential',rotationMode:'online',secretSet:true,maskedHint:'••fixture',credentialRevision:1})
  if (route.endsWith('/applications/~Y2xpZW50')) return response(application)
  if (route === '/users') return page([user,{...user,subject:'bob',username:'bob',name:'Bob fixture'},...Array.from({length:30},(_,i)=>({...user,subject:'fixture-'+i,username:'fixture-'+i,name:'Fixture user '+i}))])
  throw new Error('Unexpected in-memory transport request: '+method+' '+route)
}
const pluginEntry = '/plugin/entry.js'
const {mount} = await import(/* @vite-ignore */ pluginEntry)
let context, mounted
const controller = new AbortController()
const styles = ['/plugin/kestra-rbac-ui.css']
function makeContext(path) {
  const url = new URL(path, 'http://fixture.invalid/')
  return {contractVersion:1,apiBase:'/api/v1/main',tenant:'main',path:url.pathname.slice(1),fullPath:path,query:Object.fromEntries(url.searchParams),locale,theme:effective(),styles,signal:controller.signal,request,
    navigate:async(next,options={})=>{
      if (!(await mounted.beforeNavigate(next))) return false
      if(options.replace) history.replaceState({extensionPath:next},'',location.pathname+'?'+qs.toString())
      else history.pushState({extensionPath:next},'',location.pathname+'?'+qs.toString())
      context = makeContext(next); mounted.update(context); return true
    }}
}
context=makeContext(qs.get('path') || 'application-groups')
mounted=await mount(document.querySelector('#plugin'),context)
history.replaceState({extensionPath:context.fullPath},'',location.href)
window.addEventListener('popstate',async event=>{
  const next=event.state?.extensionPath
  if(!next)return
  if(await mounted.beforeNavigate(next)){context=makeContext(next);mounted.update(context)}
  else history.pushState({extensionPath:context.fullPath},'',location.href)
})
function updateTheme(value){theme=value;context={...context,theme:effective()};mounted.update(context)}
matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>updateTheme(theme))
let refApp, refScope
async function reference(open=true) {
  if (!open) {refApp?.unmount();refScope?.dispose();document.querySelector('#reference').shadowRoot?.replaceChildren();return}
  const container=document.querySelector('#reference')
  container.className=effective()==='dark-2'?'dark dark-2':effective()
  const root=container.shadowRoot || container.attachShadow({mode:'open'})
  const link=document.createElement('link');link.rel='stylesheet';link.href=styles[0]
  await new Promise((resolve,reject)=>{link.onload=resolve;link.onerror=reject;root.append(link)})
  const content=document.createElement('div'),portal=document.createElement('div')
  content.className=portal.className='extension-body';root.append(content,portal)
  refScope=createShadowOverlayScope({container,content,portal,signal:controller.signal,fallback:()=>content})
  const renderer=createShadowRenderer({...Vue,nodeOps,patchProp},refScope,{idInjectionKey:ID_INJECTION_KEY,namespace:'kel'})
  const adapted=shadowOverlayComponents(Vue,{KsDrawer},refScope)
  const i18n=createI18n({legacy:false,locale,messages});await registerDesignSystemI18n(i18n)
  refApp=renderer.createApp({render:()=>Vue.h(ElConfigProvider,{namespace:'kel',locale:nativeLocale(locale)},()=>Vue.h(adapted.KsDrawer,{modelValue:true,title:'Native reference',appendTo:portal,appendToBody:false,'data-test':'native-reference'},{default:()=>Vue.h('p','Native body'),footer:()=>Vue.h('div',{},[Vue.h(KsButton,{},()=>i18n.global.t('cancel')),Vue.h(KsButton,{type:'primary',icon:ContentSave,'data-test':'reference-save'},()=>i18n.global.t('save'))])}))})
  refApp.use(i18n);refApp.mount(content)
}
window.fixture={ready:true,requests,reference,locale,messages:messages[locale],navigate:path=>context.navigate(path),
  theme:updateTheme,holdWrites:()=>{holdWrites=true},releaseSave:()=>{holdWrites=false;saveRelease?.()},
  recover:()=>{scenario='normal';release?.()},
  snapshot:()=>({theme,effective:effective(),context:{locale:context.locale,theme:context.theme}})}
