// Testes de layout e uso em navegador isolado, conectado ao receptor real.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const session = JSON.parse(fs.readFileSync('work/test-session.json', 'utf8'));
const output = path.resolve('outputs');
fs.mkdirSync(output, { recursive: true });
let activeBrowser;
(async () => {
 const launchOptions={headless:true};
 if(process.env.BROWSER_EXECUTABLE_PATH)launchOptions.executablePath=process.env.BROWSER_EXECUTABLE_PATH;
 const browser = activeBrowser = await chromium.launch(launchOptions);
 const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
 await context.addInitScript(({token}) => { localStorage.setItem('ldz-token', token); localStorage.setItem('ldz-theme','silver'); }, session);
 const page = await context.newPage(); const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto(session.url); await page.waitForSelector('body.connected');await page.waitForSelector('.app-shortcut');
 await page.screenshot({ path: path.join(output, 'controle-prateado.png') });
 const cases=[];
 for (const size of [{width:390,height:844},{width:393,height:720},{width:360,height:800},{width:320,height:568},{width:844,height:390}]) {
   await page.setViewportSize(size); await page.waitForTimeout(200);
   const metrics=await page.evaluate(()=>{
     const app=document.querySelector('#app').getBoundingClientRect();
     const dpad=document.querySelector('.dpad').getBoundingClientRect();
     const controls=document.querySelector('.controls').getBoundingClientRect();
     const tabs=document.querySelector('.tabs').getBoundingClientRect();
     return { width:innerWidth,height:innerHeight,appTop:app.top,appBottom:app.bottom,appRight:app.right,padBottom:dpad.bottom,controlsTop:controls.top,tabsTop:tabs.top,scrollHeight:document.documentElement.scrollHeight,scrollWidth:document.documentElement.scrollWidth,zoom:visualViewport.scale };
   });
   if(metrics.appBottom>size.height+1||metrics.appTop<0||metrics.appRight>size.width+1||metrics.scrollHeight>size.height+1||metrics.scrollWidth>size.width+1)throw new Error('Pagina ultrapassou a tela: '+JSON.stringify(metrics));
   if(size.height>size.width&&metrics.padBottom>metrics.controlsTop+1)throw new Error('Botoes sobrepostos: '+JSON.stringify(metrics));
   await page.mouse.wheel(0,800); if(await page.evaluate(()=>scrollY)!==0)throw new Error('Rolagem de pagina detectada');
   await page.getByRole('button',{name:'Teclado',exact:true}).click();
   const text=page.locator('#remote-text');await text.fill('Teste de layout');
   if((await text.boundingBox()).width>size.width)throw new Error('Teclado ultrapassou a tela');
   await page.getByRole('button',{name:'Controle',exact:true}).click();
   cases.push({...size,result:'passed'});
 }
 await page.setViewportSize({width:390,height:844});
 await page.getByRole('button',{name:'Teclado',exact:true}).click();
 await page.locator('#remote-text').focus();
 await page.evaluate(()=>{Object.defineProperty(visualViewport,'height',{configurable:true,get:()=>330});visualViewport.dispatchEvent(new Event('resize'))});
 const compact=await page.evaluate(()=>({height:document.querySelector('#app').getBoundingClientRect().height,sendBottom:document.querySelector('#text-form .primary').getBoundingClientRect().bottom,tabsBottom:document.querySelector('.tabs').getBoundingClientRect().bottom,compact:document.body.classList.contains('keyboard-open')}));
 if(!compact.compact||compact.height!==330||compact.sendBottom>330||compact.tabsBottom>330)throw new Error('Layout com teclado recortado: '+JSON.stringify(compact));
 await page.evaluate(()=>{document.activeElement.blur();delete visualViewport.height;visualViewport.dispatchEvent(new Event('resize'))});
 await page.getByRole('button',{name:'Controle',exact:true}).click();
 await page.getByRole('button',{name:'Aparência e conexão'}).click();
 await page.locator('[data-theme-choice="midnight"]').click();
 await page.locator('#settings-close').click();
 const colors=await page.evaluate(()=>({background:getComputedStyle(document.querySelector('#app')).backgroundColor,buttons:getComputedStyle(document.querySelector('.dpad')).backgroundColor,choice:localStorage.getItem('ldz-theme')}));
 if(colors.background!=='rgb(29, 29, 31)'||colors.buttons!=='rgb(245, 245, 247)'||colors.choice!=='midnight')throw new Error('Cores divergentes: '+JSON.stringify(colors));
 await page.screenshot({path:path.join(output,'controle-meia-noite.png')});
 await page.getByRole('button',{name:'Aparência e conexão'}).click();
 await page.screenshot({path:path.join(output,'controle-temas.png')});
 await page.locator('#settings-close').click();
 // Toque duplo em voltar: dois comandos, sem ampliacao nem deslocamento.
 let commands=0;page.on('request',request=>{if(request.url().endsWith('/api/command'))commands++});
 await page.evaluate(()=>{window.tapEvents=[];for(const name of ['pointerdown','pointerup','pointercancel','click'])document.addEventListener(name,e=>window.tapEvents.push({type:name,primary:e.isPrimary,detail:e.detail,target:e.target.getAttribute('aria-label')}),true)});
 const back=page.getByRole('button',{name:'Voltar',exact:true});await back.tap();await back.tap();await page.waitForTimeout(1500);
 const scale=await page.evaluate(()=>visualViewport.scale);if(scale!==1)throw new Error('Zoom detectado');
 if(commands!==2)throw new Error('Toques duplicados ou perdidos: '+commands+' '+JSON.stringify(await page.evaluate(()=>({events:window.tapEvents,message:document.querySelector('#message').textContent}))));
 if(errors.length)throw new Error('Erros JavaScript: '+errors.join('; '));
 const result={result:'passed',viewports:cases,virtualKeyboard:compact,themeColors:colors,doubleTapCommands:commands,zoom:scale,javascriptErrors:errors};
 fs.writeFileSync(path.join(output,'validacao-web.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));await browser.close();
})().catch(async error=>{console.error(error);await activeBrowser?.close();process.exitCode=1});
