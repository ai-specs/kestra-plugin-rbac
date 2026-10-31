import {createServer} from 'node:http'
import {readFile} from 'node:fs/promises'
import {resolve, extname, sep} from 'node:path'
const root=resolve(import.meta.dirname,'../../.work/plugin-browser')
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png'}
const server=createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://127.0.0.1')
    const file=resolve(root,url.pathname.startsWith('/plugin/')?'bundle/'+url.pathname.slice(8):url.pathname==='/'?'harness/browser/index.html':'harness/'+url.pathname.slice(1))
    if(!file.startsWith(root+sep))throw new Error('outside fixture')
    res.setHeader('Content-Type',types[extname(file)] || 'application/octet-stream');res.end(await readFile(file))
  } catch {res.writeHead(404);res.end('fixture resource not found')}
})
server.listen(Number(process.env.PLUGIN_BROWSER_PORT || 4179),'127.0.0.1')
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>server.close(()=>process.exit(0)))
