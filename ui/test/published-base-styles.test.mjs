import test from 'node:test'
import assert from 'node:assert/strict'
import {rollup} from 'rollup'
import {createRequire} from 'node:module'
import {readFile} from 'node:fs/promises'
import {dirname,resolve} from 'node:path'
import config from '../vite.config.js'
const require=createRequire(import.meta.url)
test('public design-system styleBase side-effect entry survives production tree shaking and supplies native kel overlay rules',async()=>{
 const entry=require.resolve('@kestra-io/design-system/styleBase')
 const bundle=await rollup({input:'probe',treeshake:config.build.rollupOptions.treeshake,plugins:[{
  name:'public-css-probe',resolveId(id,importer){if(id==='probe')return id;if(id===entry)return id;if(id.endsWith('.css'))return resolve(dirname(importer),id)},
  async load(id){if(id==='probe')return `import ${JSON.stringify(entry)};export const mounted=true`;if(id.endsWith('.css'))return 'globalThis.__publishedCssPresent=true';return null}
 }]})
 try{const result=await bundle.generate({format:'es'});assert(result.output[0].code.includes('__publishedCssPresent'),'public CSS entry was removed from module graph')}
 finally{await bundle.close()}
 const css=await readFile(resolve(dirname(entry),'styleBase.css'),'utf8')
 assert.match(css,/\.kel-overlay\s*\{[^}]*position:\s*fixed/)
 assert.match(css,/\.kel-overlay\s*\{[^}]*height:\s*100%/)
 assert.match(css,/--kel-overlay-color-lighter:/)
})
