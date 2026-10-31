import {defineConfig} from '@playwright/test'
import {resolve} from 'node:path'
export default defineConfig({
  testDir:import.meta.dirname,testMatch:'gate.spec.mjs',fullyParallel:true,workers:process.env.CI?4:4,
  timeout:30000,retries:0,outputDir:resolve(import.meta.dirname,'../../.evidence/ui-browser/results'),
  reporter:[['./reporter.mjs'],['html',{outputFolder:resolve(import.meta.dirname,'../../.evidence/ui-browser/html'),open:'never'}],['line']],
  use:{baseURL:'http://127.0.0.1:4179',browserName:'chromium',headless:true,screenshot:'only-on-failure',trace:'retain-on-failure',actionTimeout:5000},
  webServer:{command:'node server.mjs',url:'http://127.0.0.1:4179',reuseExistingServer:false,timeout:10000}
})
