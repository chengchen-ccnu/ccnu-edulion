/** 只探测本机Prism，响应和请求均由唯一OpenAPI校验；不连接真实账号、不维护第二套Schema。 */
const fs = require('node:fs'); const path = require('node:path'); const assert = require('node:assert/strict');
const YAML = require('../web-apps/node_modules/yaml'); const Ajv = require('../web-apps/node_modules/ajv/dist/2020').default;
const addFormats = require('../web-apps/node_modules/ajv-formats');
const contract = YAML.parse(fs.readFileSync(path.resolve(__dirname,'../docs/api/openapi.yaml'),'utf8'));
assert.equal(contract.info.version,'0.31.1'); const ajv=new Ajv({strict:false});addFormats(ajv);ajv.addSchema({$id:'edu',components:contract.components});
const id='00000000-0000-4000-8000-000000000001';
/** 按契约路径和状态选择Prism响应，204须真空正文；失败保留断言，不将Mock当成真实权限验证。 */
async function probe(template,method,status,body){
  const operation=contract.paths[template][method.toLowerCase()];
  if(body){ // 将文档内部引用解析到已登记的同一组件，不复制字段定义。
    const request=ajv.compile({$ref:'edu'+operation.requestBody.content['application/json'].schema.$ref});assert.ok(request(body),JSON.stringify(request.errors));}
  const endpoint=template.replace('{userId}',id).replace('{commandId}',id);
  const response=await fetch('http://127.0.0.1:4010'+endpoint,{method,headers:{Authorization:'Bearer synthetic-mock',Prefer:`code=${status}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  assert.equal(response.status,status);const text=await response.text();
  if(status===204){assert.equal(text,'');return;}
  const schema=operation.responses[String(status)].content?.['application/json']?.schema;
  const responseRef=operation.responses[String(status)].$ref;
  const resolved=schema??(responseRef?contract.components.responses[responseRef.split('/').at(-1)].content['application/json'].schema:null);
  assert.ok(resolved);const validate=ajv.compile({$ref:'edu'+resolved.$ref});assert.ok(validate(JSON.parse(text)),JSON.stringify(validate.errors));
}
(async()=>{
  const root='/api/v1/admin/users/{userId}';
  await probe('/api/v1/admin/users','GET',200);await probe(root,'GET',200);
  await probe(root+'/permission-previews','POST',201,{action:'BAN'});
  await probe(root+'/permission-commands','POST',202,{clientRequestId:id,previewId:id,expectedRevision:0,action:'BAN',reason:'合成测试权限变更理由',confirmed:true});
  const latest=root+'/permission-commands/latest';for(const status of [200,204,401,403,404,422,429,503])await probe(latest,'GET',status);
  await probe('/api/v1/admin/permission-commands/{commandId}','GET',200);
  await probe('/api/v1/admin/permission-commands/{commandId}/retry','POST',202,{clientRequestId:id,expectedAttempts:3});
  console.log('PASS OpenAPI 0.31.1 Prism: seven routes, latest eight statuses and empty 204; no real domain writes');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
