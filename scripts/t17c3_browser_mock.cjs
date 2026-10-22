/** T17C.3 确定性浏览器故障夹具，显式Mock。仅回环监听，无SMTP/数据库/真实Cookie，不记录请求正文或秘密。 */
const http = require('node:http'); const fs = require('node:fs'); const path = require('node:path');
const YAML = require('../web-apps/node_modules/yaml'); const Ajv = require('../web-apps/node_modules/ajv/dist/2020').default;
const formats = require('../web-apps/node_modules/ajv-formats');
const root = path.resolve(__dirname, '..'); const directory = path.join(root, '.m2/t17c3-browser');
const scenarioFile = path.join(directory, 'mock-scenario.json');
const spec = YAML.parse(fs.readFileSync(path.join(root, 'docs/api/openapi.yaml'), 'utf8'));
const ajv = new Ajv({strict:false}); formats(ajv); ajv.addSchema({$id:'edu',components:spec.components});
const id = '00000000-0000-4000-8000-000000000001'; const other = '00000000-0000-4000-8000-000000000002';
const counts = {}; fs.mkdirSync(directory,{recursive:true});
if (!fs.existsSync(scenarioFile)) fs.writeFileSync(scenarioFile,JSON.stringify({mode:'normal'}));
/** 每条响应及非空请求按唯一OpenAPI验证；验证失败只记布尔，不回显密码/凭证/请求。 */
function validate(operation,status,body,input) {
  if(input && operation.requestBody) {
    const check = ajv.compile({$ref:'edu'+operation.requestBody.content['application/json'].schema.$ref});
    if(!check(input)) throw new Error('Mock request violates OpenAPI');
  }
  const response = operation.responses[String(status)];
  const resolved = response.$ref ? spec.components.responses[response.$ref.split('/').at(-1)] : response;
  if(status===204) return;
  const check = ajv.compile({$ref:'edu'+resolved.content['application/json'].schema.$ref});
  if(!check(body)) throw new Error('Mock response violates OpenAPI');
}
http.createServer(async (request,response)=>{
  response.setHeader('Access-Control-Allow-Origin', request.headers.origin ?? 'http://127.0.0.1:15174');
  response.setHeader('Access-Control-Allow-Credentials','true'); response.setHeader('Access-Control-Allow-Headers','Authorization,Content-Type');
  response.setHeader('Access-Control-Allow-Methods','GET,POST,OPTIONS'); response.setHeader('Access-Control-Expose-Headers','Retry-After');
  if(request.method==='OPTIONS'){response.writeHead(204);response.end();return;}
  const pathname = new URL(request.url,'http://127.0.0.1').pathname;
  const scenario = JSON.parse(fs.readFileSync(scenarioFile,'utf8')); const mode = scenario.mode;
  let template = pathname.replace(/\/invitations\/[a-f\d-]{36}/i,'/invitations/{invitationId}');
  const operation = spec.paths[template]?.[request.method.toLowerCase()];
  const chunks=[]; for await(const chunk of request) chunks.push(chunk);
  let input; try { input=chunks.length?JSON.parse(Buffer.concat(chunks)):undefined; } catch {response.writeHead(400);response.end();return;}
  counts[request.method+' '+template]=(counts[request.method+' '+template]??0)+1;
  fs.writeFileSync(path.join(directory,'mock-counts.json'),JSON.stringify(counts));
  let status=200; let body;
  if(pathname==='/api/v1/auth/refresh' || pathname==='/api/v1/auth/login') body={accessToken:'synthetic-mock-token',tokenType:'Bearer',expiresIn:900};
  else if(pathname==='/api/v1/auth/logout'){status=204;body=null;}
  else if(pathname==='/api/v1/users/me') body={id:other,displayName:'admin_test_mock',roles:['ADMIN'],permissions:['administration:access'],sessionVersion:0};
  else if(pathname.includes('/admin/invitations')) {
    status=request.method==='GET'?200:202;
    if(mode.startsWith('read') && request.method==='GET') status=Number(mode.slice(4));
    if(mode==='createUnknown' && pathname.endsWith('/invitations') && request.method==='POST') status=503;
    body={invitationId:id,maskedEmail:'sy***@example.invalid',deliveryStatus:mode==='pending'?'PENDING':mode==='failed'?'FAILED':'SENT',
      acceptanceStatus:mode==='accepted'?'ACCEPTED':mode==='expired'?'EXPIRED':'AWAITING',revision:3,sendAttempts:mode==='budget'?6:1,
      expiresAt:new Date(Date.now()+(mode==='expired'?-1:86400000)).toISOString(),resendAfter:new Date(Date.now()-1000).toISOString(),replayed:false};
  } else if(pathname==='/api/v1/auth/admin-invitations/accept') {status=mode==='acceptUnknown'?503:mode==='accept422'?422:201;body={userId:id};}
  else {status=503;body=null;}
  if(status>=400) body={code:'COMMON.UNEXPECTED_ERROR',message:'合成Mock故障',traceId:'synthetic',timestamp:new Date().toISOString(),path:pathname,fieldErrors:[]};
  try {if(operation)validate(operation,status,body,input);}
  catch {response.writeHead(500);response.end('Mock schema validation failed');return;}
  response.setHeader('Content-Type','application/json'); if(status===429)response.setHeader('Retry-After','2');
  response.writeHead(status);response.end(status===204?'':JSON.stringify(body));
}).listen(14010,'127.0.0.1',()=>console.log('T17C.3 explicit OpenAPI Mock listening on loopback14010; no domain writes'));
